package com.tailtopia.shop.order.notify;

import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 待提醒登记与窗口收拢（Story 3-4 AC1 / AC3）。
 *
 * <p>🔴🔴 <b>这个类存在的首要理由，是让「登记失败」不会变成「支付回滚」。</b>
 * 调用点 {@code ShopOrderPaidHandler.onPaid} 是
 * {@code @Transactional(propagation = MANDATORY)} —— 它跑在支付回调的事务里。
 * 在那里抛任何异常，都会把整个回调事务连同意图的 {@code markPaid} 一起回滚，
 * <b>那才是真的丢账</b>（该方法里已有两处注释把这条写死了）。
 *
 * <p>所以登记用 {@link Propagation#REQUIRES_NEW} 开一个独立事务：
 * <ul>
 *   <li>它失败了，回滚的只有它自己，支付事务毫发无伤；</li>
 *   <li>调用方再包一层 try/catch 把异常吃掉、只记 warn。</li>
 * </ul>
 * 两层都要 —— 独立事务解决「回滚传染」，try/catch 解决「异常传染」。
 *
 * <p>🔴 <b>事务边界的第二条铁律：出网调用不在事务里。</b>
 * 本类把「收拢窗口」「回写成功」「回写失败」拆成三个独立的短事务，
 * 中间那次 Lark HTTP（默认超时 10s）由 {@link ShopOrderNotifyScanner} 在<b>事务之外</b>发起。
 * 若把发送包进事务，一个连接池连接会被一次网络往返占住整整 10 秒 ——
 * 这正是 {@code ShopOrderExpiryScanner} 的写法（薄扫描器 + 事务性 service）想避免的。
 */
@Service
public class ShopOrderNotifyService {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderNotifyService.class);

    private final ShopOrderNotifyQueueRepository queue;
    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final ShopOrderNotifyProperties props;

    public ShopOrderNotifyService(ShopOrderNotifyQueueRepository queue, ShopOrderRepository orders,
            ShopOrderLineRepository orderLines, ShopOrderNotifyProperties props) {
        this.queue = queue;
        this.orders = orders;
        this.orderLines = orderLines;
        this.props = props;
    }

    /** 一个窗口收拢出来的待发批次：要发的文本行，以及发成功后该回写的队列行 id。 */
    public record Batch(List<Long> entryIds, List<ShopOrderNotifyMessage.Line> lines) {
    }

    /**
     * 登记一笔待提醒订单。
     *
     * <p>幂等：支付回调有双通道（回调 + 轮询），同一订单的到账事件可能到两次。
     * 先 {@code existsBy} 挡一次，唯一约束兜底 —— <b>不引入任何分布式锁</b>。
     *
     * <p>🔴 <b>本方法自己不抛</b>（除了 {@code REQUIRES_NEW} 事务提交阶段的异常，
     * 那由调用方的 try/catch 接住）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(long shopOrderId) {
        if (!props.isLive()) {
            // 🔴 复审 #14：mode=off 时**根本不登记**，而不是「先攒着、等开了再发」。
            //    原实现只在扫描器那头判 isLive，行却照样入队。于是运营在 OD-5 定下
            //    receive_id、把 mode 切到 live 的那一天，几个月的存量单会被当成新单
            //    从最旧的一条开始重播（collectWindow 按 created_at 升序取），
            //    刷屏约几十分钟，而运营分不清哪些是真的新订单。
            //    这批历史单要么早发完了、要么已由别的渠道处理 —— 它们不是「待办」。
            return;
        }
        if (queue.existsByShopOrderId(shopOrderId)) {
            return; // 已登记过，重复到账事件不再产生第二行
        }
        queue.save(ShopOrderNotifyQueueEntry.pending(shopOrderId));
        log.debug("新订单已登记待提醒 orderId={}", shopOrderId);
    }

    /**
     * 收拢一个窗口的待发订单（AC3）。
     *
     * <p>🔴 右端取 {@code now} 而不是 {@code now - window}：窗口的意义是
     * 「把这段时间内到的订单攒成<b>一条</b>」，不是「让每一单都先等满一个窗口」。
     * 等满会把时延从「≤ 扫描周期」推到「窗口 + 扫描周期」，直接顶穿 SHOP-NFR-03 的 10 分钟。
     * 窗口长度在这里的作用是<b>切分</b>：第一行的登记时刻 + window 之外的留到下一轮，
     * 于是「跨窗口的订单分成两条消息发」（AC3 第二条）。
     *
     * @return 空 = 本轮没有要发的（AC3 末条：空窗口不发空消息）
     */
    @Transactional
    public Optional<Batch> collectWindow() {
        List<ShopOrderNotifyQueueEntry> batch =
                queue.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        ShopOrderNotifyQueueEntry.Status.PENDING, Instant.now(),
                        PageRequest.of(0, Math.max(1, props.getMaxOrdersPerMessage())));
        if (batch.isEmpty()) {
            return Optional.empty();
        }

        Instant windowEnd = batch.get(0).getCreatedAt()
                .plus(Math.max(1, props.getWindowMinutes()), ChronoUnit.MINUTES);
        // 🔴 复审 #14 的第二道闸：即便 enqueue 那头没拦住（配置中途改动、
        //    或长时间故障后积压），也绝不把一天前的单当新单报出去。
        Instant staleBefore = Instant.now()
                .minus(Math.max(1, props.getStaleAfterHours()), ChronoUnit.HOURS);

        List<Long> ids = new ArrayList<>();
        List<ShopOrderNotifyMessage.Line> lines = new ArrayList<>();
        for (ShopOrderNotifyQueueEntry e : batch) {
            if (e.getCreatedAt().isAfter(windowEnd)) {
                break; // 已按 created_at 升序，后面的都在窗口外
            }
            if (e.getCreatedAt().isBefore(staleBefore)) {
                // 清出队列但**不发**：这单早过了「催今天的货」的有效期。
                e.markSent();
                queue.save(e);
                log.warn("新订单提醒已过期，跳过不发 orderId={} createdAt={}",
                        e.getShopOrderId(), e.getCreatedAt());
                continue;
            }
            ShopOrder order = orders.findById(e.getShopOrderId()).orElse(null);
            if (order == null) {
                // 订单没了（脏数据 / 跨环境）—— 直接标 SENT 清出队列，别每轮都来捞一次。
                e.markSent();
                queue.save(e);
                continue;
            }
            ids.add(e.getId());
            lines.add(toLine(order));
        }
        return lines.isEmpty() ? Optional.empty() : Optional.of(new Batch(ids, lines));
    }

    /**
     * 把冷却期已过的 {@code FAILED} 行放回队列（复审 #15）。
     *
     * <p>🔴 <b>FAILED 不该是不可恢复的终态</b>。原实现里 {@code collectWindow} 只查 PENDING，
     * 全仓又没有任何重入队路径、没有后台入口、没有恢复跑批 —— 一次约 15 分钟的 Lark 故障
     * （5 分钟扫一次 × 3 次重试）就足以把整批积压永久判死，事后把 receive_id 改对也救不回。
     * 那批订单于是彻底从发货信号里消失，而用户的钱已经付了。
     *
     * <p>代价是配置若长期错误会周期性重试 —— 但「多试几次」远好过「永久丢掉发货信号」。
     *
     * @return 本轮放回的行数（0 = 没有可恢复的）
     */
    @Transactional
    public int requeueFailed() {
        Instant cooledBefore = Instant.now()
                .minus(Math.max(1, props.getRetryCooldownMinutes()), ChronoUnit.MINUTES);
        List<ShopOrderNotifyQueueEntry> dead = queue.findByStatusAndUpdatedAtBefore(
                ShopOrderNotifyQueueEntry.Status.FAILED, cooledBefore,
                PageRequest.of(0, Math.max(1, props.getMaxOrdersPerMessage())));
        for (ShopOrderNotifyQueueEntry e : dead) {
            e.requeue();
            queue.save(e);
        }
        if (!dead.isEmpty()) {
            log.warn("新订单提醒：{} 行冷却期已过，放回队列重试", dead.size());
        }
        return dead.size();
    }

    /** 投递成功：整批转 {@code SENT} + {@code sent_at}（AC4）。 */
    @Transactional
    public void markSent(List<Long> entryIds) {
        for (ShopOrderNotifyQueueEntry e : queue.findAllById(entryIds)) {
            e.markSent();
            queue.save(e);
        }
    }

    /**
     * 投递失败：整批 {@code retry_count + 1}，保持 {@code PENDING} 下一轮重试；
     * 超过 {@code max-retries} 转 {@code FAILED} 并记 error，不再重试（AC4）。
     *
     * <p>🔴 失败<b>只落队列行与日志</b>：不写通知中心、不给用户任何感知、更不碰订单（AC7）。
     */
    @Transactional
    public void markFailed(List<Long> entryIds) {
        int maxRetries = Math.max(1, props.getMaxRetries());
        for (ShopOrderNotifyQueueEntry e : queue.findAllById(entryIds)) {
            if (e.markFailedAttempt(maxRetries)) {
                log.error("新订单提醒重试超限，放弃 orderId={} retries={}", e.getShopOrderId(),
                        e.getRetryCount());
            }
            queue.save(e);
        }
    }

    /**
     * 🔒 只取三样：订单号、金额、件数。
     *
     * <p>{@code ShopOrder} 上就挂着收件人姓名 / 电话 / 详细地址 —— 离得最近、最容易手滑。
     * 转成 {@link ShopOrderNotifyMessage.Line} 这个三字段的值对象是刻意的：
     * 渲染那一侧根本拿不到别的东西。
     */
    private ShopOrderNotifyMessage.Line toLine(ShopOrder order) {
        int itemCount = 0;
        for (ShopOrderLine l : orderLines.findByOrderIdOrderByIdAsc(order.getId())) {
            itemCount += l.getQty();
        }
        // 🔴 Story 4-3 已切换：读库列 display_no。
        //   运营在 Lark 群里看到的号，要能直接粘进后台搜索框搜出这一单。
        return new ShopOrderNotifyMessage.Line(order.getDisplayNo(), order.getTotalAmount(),
                itemCount);
    }
}

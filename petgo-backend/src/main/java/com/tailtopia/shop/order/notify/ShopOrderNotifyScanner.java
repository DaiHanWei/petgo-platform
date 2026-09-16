package com.tailtopia.shop.order.notify;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 新订单汇总提醒扫描器（Story 3-4 · SHOP-FR-24）。
 *
 * <p>范式照 {@code ShopOrderExpiryScanner}：<b>薄扫描器</b> —— 自己不带事务、不写库，
 * 只负责节奏与 try/catch，实际读写全在 {@link ShopOrderNotifyService} 的短事务里。
 * Spring 原生 {@code @Scheduled} + cron 可配 + DB 唯一约束去重。
 * 🔴 <b>不引入 Quartz / Kafka / Redis Stream / 任何队列或调度中间件</b>（SHOP-NFR-06）。
 *
 * <p>⚠️ <b>关于 AC4 里的「{@code @Async} 投递」</b>：本类<b>刻意没有</b>把发送做成 {@code @Async}，
 * 与 AC4 同时点名的另一个范式 {@code ShopOrderExpiryScanner} 保持一致（它全篇无 {@code @Async}）。
 * 理由是二者的形状不同：{@code ScheduledPushJob} 的 {@code @Async} 是<b>逐条</b>投递几百条互相独立的推送，
 * 异步化省的是串行等待；而本 story 一个窗口<b>只发一条</b> HTTP，异步化省不到任何东西，却会引入一个真问题 ——
 * {@code @Scheduled} 立刻返回后队列行仍是 {@code PENDING}，下一次 cron 唤醒会把同一批再捞一次，
 * <b>运营会收到重复的汇总消息</b>。要修就得加 {@code SENDING} 中间态与崩溃后的回收扫描，
 * 那是比这条提醒本身重得多的机械。已记入 Completion Notes。
 *
 * <p>🔴 <b>汇总，绝不每单一条</b>（AC3）：一个窗口内的订单合成**一条**消息。
 * 每单一条会让运营在促销时段被刷屏，而被刷屏的通知等于没有通知。
 *
 * <p>🔴 <b>发送失败绝不影响订单</b>（AC7）：本类只读订单、只写队列行。
 * 订单状态、库存、资金一个字节都不碰。
 */
@Component
public class ShopOrderNotifyScanner {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderNotifyScanner.class);

    /** 夜间静默按印尼当地时间判断（运营在雅加达）。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final ShopOrderNotifyService notify;
    private final LarkMessageClient lark;
    private final ShopOrderNotifyProperties props;

    public ShopOrderNotifyScanner(ShopOrderNotifyService notify, LarkMessageClient lark,
            ShopOrderNotifyProperties props) {
        this.notify = notify;
        this.lark = lark;
        this.props = props;
    }

    /**
     * 默认每 5 分钟扫一次（cron 可经配置覆盖）。
     *
     * <p>扫描周期必须**短于**汇总窗口。由于窗口右端取 {@code now}（见
     * {@link ShopOrderNotifyService#collectWindow()}），实际时延是「订单到达 → 下一次扫描」
     * ≤ 5 分钟，满足 SHOP-NFR-03 的 10 分钟。
     */
    @Scheduled(cron = "${petgo.shop.order-notify.cron:0 */5 * * * *}", zone = "UTC")
    public void scan() {
        if (!props.isLive()) {
            // 🔴 mode=off（或收件人未配）→ **静默跳过，零出网**，队列行保持 PENDING。
            //    未配置的环境（本地 / CI / prod 未开）不会因为这个任务产生任何副作用。
            return;
        }
        if (isQuietNow()) {
            return; // 夜间静默：行保持 PENDING，等天亮那一轮一起发
        }

        Optional<ShopOrderNotifyService.Batch> batch;
        try {
            batch = notify.collectWindow();
        } catch (RuntimeException e) {
            log.warn("新订单提醒收拢失败 reason={}", e.getClass().getSimpleName());
            return;
        }
        if (batch.isEmpty()) {
            return; // 🔴 空窗口不发空消息（AC3 末条）
        }

        ShopOrderNotifyService.Batch b = batch.get();
        try {
            // 🔴 出网在事务之外 —— 见 ShopOrderNotifyService 类注释第二条铁律。
            lark.sendText(ShopOrderNotifyMessage.render(b.lines()));
        } catch (RuntimeException ex) {
            log.warn("新订单提醒发送失败，将重试 count={} reason={}", b.lines().size(),
                    ex.getClass().getSimpleName());
            safely(() -> notify.markFailed(b.entryIds()), "标记失败");
            return;
        }
        // 走到这里消息已经发出去了：回写失败只会让下一轮重发（运营多收一条），
        // 绝不能把它当成「发送失败」去累加 retry_count。
        safely(() -> notify.markSent(b.entryIds()), "回写已发送");
        log.info("新订单提醒已发送 count={}", b.lines().size());
    }

    private void safely(Runnable action, String what) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("新订单提醒{}失败 reason={}", what, e.getClass().getSimpleName());
        }
    }

    /** 夜间静默窗口判定（按 WIB 当前时刻）。 */
    boolean isQuietNow() {
        return isQuietAt(LocalTime.now(WIB).getHour());
    }

    /**
     * 夜间静默窗口判定的纯函数版：给定小时（WIB，0–23）判断是否静默。
     *
     * <p>🔴 <b>跨午夜必须走「或」分支</b>：运营真正想要的窗口是 22:00–08:00，
     * 此时 {@code start(22) > end(8)}。若一律写成 {@code start <= hour && hour < end}，
     * 没有任何小时能满足 —— 静默开关打开了却永远不生效，而且不会报任何错。
     * 抽成纯函数是为了能在 L0 逐小时钉死，不用等真实时钟转到半夜。
     */
    boolean isQuietAt(int hour) {
        if (!props.isQuietHoursEnabled()) {
            return false; // 🔴 默认关闭 —— OD-5 定值前不启用
        }
        int start = props.getQuietStartHour();
        int end = props.getQuietEndHour();
        return start <= end ? (hour >= start && hour < end) : (hour >= start || hour < end);
    }
}

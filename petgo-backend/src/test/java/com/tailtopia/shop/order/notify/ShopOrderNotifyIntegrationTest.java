package com.tailtopia.shop.order.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.ShopTokenGenerator;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：新订单提醒队列与窗口收拢（Story 3-4 AC1 / AC2 / AC3）。
 *
 * <p>⚠️ <b>云端未执行</b> —— 需 Docker postgres + redis，待本地验收。
 *
 * <p>本类<b>不</b>覆盖真实的 Lark 出网（那是 L2）。它覆盖的是队列这一侧的语义：
 * 幂等、窗口切分、空窗口、重试与放弃 —— 这些全部只跟 DB 状态机有关。
 *
 * <p>🔴 <b>作用域纪律</b>：{@link ShopOrderNotifyService#collectWindow()} 是<b>全局</b>查询
 * （按 status + created_at 取前 {@code max-orders-per-message} 行），而
 * {@code shop_order_notify_queue} 没有任何外键、不会被谁级联删掉。
 * 原来这里几条用例直接断言收拢结果的<b>条数</b>甚至「整张表为空」——
 * 在全新 scratch 库上碰巧能绿，在任何跑过几轮的真实开发库上永久红
 * （本仓踩过多次的「共享 dev 库污染」）。更隐蔽的是：同一次运行里前面用例留下的
 * PENDING 行也会被后面的用例捞进同一个批次，跟外部污染一模一样。
 * 两手处理：① {@link #ownTheQueue()} 让每个用例独占这张表；
 * ② 断言改成按<b>本用例自己造的订单展示号</b>比对，而不是数条数。
 *
 * <p>⚠️ <b>为什么要 {@code mode=live}</b>：{@link ShopOrderNotifyService#enqueue(long)} 现在带
 * {@code isLive()} 闸门（复审 #14：off 时不入队，免得切 live 那天重播存量单）。
 * 而 {@code mode} 默认 {@code off}，不覆盖的话本类每一条都会静默地一行都登记不上。
 * {@code cron=-} 同时把 {@link ShopOrderNotifyScanner} 的定时任务关掉 ——
 * 否则 live 配置下扫描器会在测试中途真的去连 Lark（出网 + 10s 超时）。
 */
@TestPropertySource(properties = {
        "petgo.shop.order-notify.mode=live",
        "petgo.shop.order-notify.receive-id=oc_integration_test_fake",
        "petgo.shop.order-notify.cron=-",
})
class ShopOrderNotifyIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ShopOrderNotifyService notify;
    @Autowired
    private ShopOrderNotifyQueueRepository queue;
    @Autowired
    private ShopOrderRepository orders;
    @Autowired
    private ShopTokenGenerator tokens;
    @Autowired
    private com.tailtopia.shop.order.service.ShopOrderDisplayNoGenerator displayNos;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 每个用例独占这张队列表。
     *
     * <p>它是一张<b>纯工作队列</b>：生产里由 {@code ShopOrderNotifyScanner} 捞干净，
     * 没有外键、没人引用它、删掉不影响任何订单数据（AC7 正是钉这一点）。
     * 不清的话，{@code collectWindow()} 会把别人留下的 PENDING 行一并捞进批次 ——
     * 而「空窗口不发空消息」这条语义压根无法用过滤表达，必须表是空的才谈得上。
     */
    @BeforeEach
    void ownTheQueue() {
        jdbc.update("DELETE FROM shop_order_notify_queue");
    }

    /** 批次里的订单展示号。用来只挑出<b>本用例自己造的那几单</b>，别人的行进来了也不影响。 */
    private static List<String> displayNosIn(Optional<ShopOrderNotifyService.Batch> batch) {
        return batch.map(b -> b.lines().stream()
                        .map(ShopOrderNotifyMessage.Line::displayNo).toList())
                .orElseGet(List::of);
    }

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "ntf" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class,
                "ntf" + n);
    }

    private ShopOrder seedOrder(long amount) {
        return orders.save(ShopOrder.place(tokens.generate(), displayNos.generate(Instant.now()), Instant.now(), seedUser(), amount, 0L, 0L,
                new AddressSnapshot("Budi Santoso", "+628123456789", "DKI Jakarta",
                        "Jakarta Selatan", "Kebayoran Baru", "Jl. Melawai IV No. 12", "12160")));
    }

    /** 把队列行的登记时刻往前挪，用来构造「跨窗口」而不用真的等。 */
    private void backdate(long shopOrderId, int minutesAgo) {
        jdbc.update("UPDATE shop_order_notify_queue SET created_at = ? WHERE shop_order_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)),
                shopOrderId);
    }

    // ---------- AC1：登记与幂等 ----------

    @Test
    @DisplayName("🔴 同一订单重复到账事件只产生一行（AC1 幂等）")
    void enqueueIsIdempotent() {
        ShopOrder o = seedOrder(100_000L);

        notify.enqueue(o.getId());
        notify.enqueue(o.getId());
        notify.enqueue(o.getId());

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM shop_order_notify_queue WHERE shop_order_id = ?",
                Integer.class, o.getId());
        assertThat(rows).as("支付回调是双通道（回调 + 轮询），重复投递是常态").isEqualTo(1);
    }

    @Test
    @DisplayName("AC2：唯一约束是幂等的最终兜底（绕过 service 直接插第二行必须被拒）")
    void uniqueConstraintIsTheBackstop() {
        ShopOrder o = seedOrder(100_000L);
        notify.enqueue(o.getId());

        boolean rejected;
        try {
            jdbc.update("INSERT INTO shop_order_notify_queue "
                    + "(shop_order_id, status, retry_count, created_at, updated_at) "
                    + "VALUES (?, 'PENDING', 0, now(), now())", o.getId());
            rejected = false;
        } catch (Exception e) {
            rejected = true;
        }
        assertThat(rejected).as("uq_shop_order_notify_queue_order 必须拦住第二行").isTrue();
    }

    @Test
    @DisplayName("AC2：status 的 CHECK 拦得住未知态")
    void statusCheckRejectsUnknownValue() {
        ShopOrder o = seedOrder(100_000L);
        notify.enqueue(o.getId());

        boolean rejected;
        try {
            jdbc.update("UPDATE shop_order_notify_queue SET status = 'WHATEVER' "
                    + "WHERE shop_order_id = ?", o.getId());
            rejected = false;
        } catch (Exception e) {
            rejected = true;
        }
        assertThat(rejected).isTrue();
    }

    // ---------- AC3：汇总与窗口切分 ----------

    @Test
    @DisplayName("🔴 同窗口三笔订单收拢成**一个批次**（发出去就是一条消息）")
    void threeOrdersInOneWindowCollectIntoOneBatch() {
        ShopOrder a = seedOrder(285_000L);
        ShopOrder b = seedOrder(100_000L);
        ShopOrder c = seedOrder(1_250_000L);
        notify.enqueue(a.getId());
        notify.enqueue(b.getId());
        notify.enqueue(c.getId());

        Optional<ShopOrderNotifyService.Batch> batch = notify.collectWindow();

        assertThat(batch).isPresent();
        // 🔴 断言「这三个号都在同一批里」，而不是 hasSize(3)。
        //    条数是全局口径，任何一行别人的残留 PENDING 都会把它顶翻；
        //    而「三笔收拢成一批」这条语义本身只跟这三个号在不在同一批有关。
        assertThat(displayNosIn(batch))
                .as("三笔必须在同一个批次里，否则运营会被刷屏")
                .contains(a.getDisplayNo(), b.getDisplayNo(), c.getDisplayNo());
        assertThat(batch.get().entryIds()).hasSameSizeAs(batch.get().lines());
    }

    @Test
    @DisplayName("🔴 跨窗口的订单分成两批（AC3 第二条）")
    void ordersAcrossWindowsSplitIntoTwoBatches() {
        ShopOrder old = seedOrder(100_000L);
        ShopOrder fresh = seedOrder(200_000L);
        notify.enqueue(old.getId());
        notify.enqueue(fresh.getId());
        // 默认窗口 10 分钟：把第一笔挪到 30 分钟前，第二笔就落在窗口外。
        backdate(old.getId(), 30);

        // 同上：断言「谁在这一批、谁不在」，不断言批次的条数。
        Optional<ShopOrderNotifyService.Batch> first = notify.collectWindow();
        assertThat(displayNosIn(first))
                .as("第一轮只带走老那一笔")
                .contains(old.getDisplayNo())
                .doesNotContain(fresh.getDisplayNo());

        notify.markSent(first.orElseThrow().entryIds());

        Optional<ShopOrderNotifyService.Batch> second = notify.collectWindow();
        assertThat(displayNosIn(second))
                .as("新的那一笔留到下一条消息")
                .contains(fresh.getDisplayNo());
    }

    @Test
    @DisplayName("🔴 队列为空时收拢返回空 —— 不发空消息（AC3 末条）")
    void emptyQueueCollectsNothing() {
        // ⚠️ 这是本类唯一一条**无法**靠「只看自己造的数据」表达的断言 ——
        //    它验的就是「表里什么都没有时不返批次」。原来它没有任何清理：
        //    全新 scratch 库上绿，任何跑过几轮的开发库（或同一次运行里前面用例
        //    留下的 PENDING 行）上永久红，而红的原因跟空窗口语义毫无关系。
        //    现在由 @BeforeEach 的 DELETE 保证前置条件真的成立。
        assertThat(notify.collectWindow()).isEmpty();
    }

    @Test
    @DisplayName("订单被删/脏数据时该行直接清出队列，不会每轮都来捞一次")
    void orphanRowIsRetiredNotRetried() {
        ShopOrder o = seedOrder(100_000L);
        notify.enqueue(o.getId());
        jdbc.update("DELETE FROM shop_orders WHERE id = ?", o.getId());

        // 只看「这一单的号没被收拢进去」，不看整批为不为空。
        assertThat(displayNosIn(notify.collectWindow()))
                .as("订单都没了，它不该再出现在任何批次里")
                .doesNotContain(o.getDisplayNo());
        String status = jdbc.queryForObject(
                "SELECT status FROM shop_order_notify_queue WHERE shop_order_id = ?", String.class,
                o.getId());
        assertThat(status).isEqualTo("SENT");
    }

    // ---------- AC4：回写、重试与放弃 ----------

    @Test
    @DisplayName("AC4：投递成功整批转 SENT 且写 sent_at")
    void markSentStampsTheBatch() {
        ShopOrder o = seedOrder(100_000L);
        notify.enqueue(o.getId());
        ShopOrderNotifyService.Batch batch = notify.collectWindow().orElseThrow();

        notify.markSent(batch.entryIds());

        ShopOrderNotifyQueueEntry e = queue.findAll().stream()
                .filter(x -> x.getShopOrderId().equals(o.getId())).findFirst().orElseThrow();
        assertThat(e.getStatus()).isEqualTo(ShopOrderNotifyQueueEntry.Status.SENT);
        assertThat(e.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("🔴 AC4：失败前两次保持 PENDING 继续重试，第三次转 FAILED 不再捞")
    void retriesThenGivesUp() {
        ShopOrder o = seedOrder(100_000L);
        notify.enqueue(o.getId());
        ShopOrderNotifyService.Batch batch = notify.collectWindow().orElseThrow();

        notify.markFailed(batch.entryIds());
        assertThat(reload(o).getStatus())
                .as("第 1 次失败后必须还能重试 —— 一次网络抖动不该丢掉提醒")
                .isEqualTo(ShopOrderNotifyQueueEntry.Status.PENDING);

        notify.markFailed(batch.entryIds());
        assertThat(reload(o).getStatus()).isEqualTo(ShopOrderNotifyQueueEntry.Status.PENDING);

        notify.markFailed(batch.entryIds());
        assertThat(reload(o).getStatus())
                .as("默认 max-retries=3，撞满就别再每轮撞同一堵墙")
                .isEqualTo(ShopOrderNotifyQueueEntry.Status.FAILED);
        assertThat(reload(o).getRetryCount()).isEqualTo(3);

        assertThat(displayNosIn(notify.collectWindow()))
                .as("FAILED 的行不该再被收拢")
                .doesNotContain(o.getDisplayNo());
    }

    // ---------- AC7：订单本身不受影响 ----------

    @Test
    @DisplayName("🔴 AC7：提醒队列反复失败，订单状态/金额一个字节都不变")
    void notifyFailureNeverTouchesTheOrder() {
        ShopOrder o = seedOrder(285_000L);
        var before = orders.findById(o.getId()).orElseThrow();
        var statusBefore = before.getStatus();
        long amountBefore = before.getTotalAmount();

        notify.enqueue(o.getId());
        ShopOrderNotifyService.Batch batch = notify.collectWindow().orElseThrow();
        notify.markFailed(batch.entryIds());
        notify.markFailed(batch.entryIds());
        notify.markFailed(batch.entryIds());

        ShopOrder after = orders.findById(o.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(statusBefore);
        assertThat(after.getTotalAmount()).isEqualTo(amountBefore);
    }

    private ShopOrderNotifyQueueEntry reload(ShopOrder o) {
        return queue.findAll().stream().filter(x -> x.getShopOrderId().equals(o.getId()))
                .findFirst().orElseThrow();
    }
}

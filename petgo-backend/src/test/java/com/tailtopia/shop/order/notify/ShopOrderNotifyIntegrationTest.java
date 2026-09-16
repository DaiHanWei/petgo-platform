package com.tailtopia.shop.order.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.ShopTokenGenerator;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：新订单提醒队列与窗口收拢（Story 3-4 AC1 / AC2 / AC3）。
 *
 * <p>⚠️ <b>云端未执行</b> —— 需 Docker postgres + redis，待本地验收。
 *
 * <p>本类<b>不</b>覆盖真实的 Lark 出网（那是 L2）。它覆盖的是队列这一侧的语义：
 * 幂等、窗口切分、空窗口、重试与放弃 —— 这些全部只跟 DB 状态机有关。
 */
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
        assertThat(batch.get().lines()).as("三笔必须在同一个批次里，否则运营会被刷屏").hasSize(3);
        assertThat(batch.get().entryIds()).hasSize(3);
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

        ShopOrderNotifyService.Batch first = notify.collectWindow().orElseThrow();
        assertThat(first.lines()).as("第一轮只带走老那一笔").hasSize(1);

        notify.markSent(first.entryIds());

        ShopOrderNotifyService.Batch second = notify.collectWindow().orElseThrow();
        assertThat(second.lines()).as("新的那一笔留到下一条消息").hasSize(1);
    }

    @Test
    @DisplayName("🔴 队列为空时收拢返回空 —— 不发空消息（AC3 末条）")
    void emptyQueueCollectsNothing() {
        assertThat(notify.collectWindow()).isEmpty();
    }

    @Test
    @DisplayName("订单被删/脏数据时该行直接清出队列，不会每轮都来捞一次")
    void orphanRowIsRetiredNotRetried() {
        ShopOrder o = seedOrder(100_000L);
        notify.enqueue(o.getId());
        jdbc.update("DELETE FROM shop_orders WHERE id = ?", o.getId());

        assertThat(notify.collectWindow()).isEmpty();
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

        assertThat(notify.collectWindow()).as("FAILED 的行不该再被收拢").isEmpty();
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

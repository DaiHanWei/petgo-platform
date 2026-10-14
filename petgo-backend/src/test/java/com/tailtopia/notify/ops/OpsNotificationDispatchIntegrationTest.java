package com.tailtopia.notify.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.OpsAudienceQuery;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignDraft;
import com.tailtopia.notify.ops.domain.OpsCampaignStatus;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.domain.OpsRecipient;
import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.ops.service.OpsNotificationCampaignService;
import com.tailtopia.notify.ops.service.OpsNotificationDispatcher;
import com.tailtopia.notify.ops.service.OpsNotificationScanner;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.ops.OpsTokenGenerator;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（需 Postgres + Redis，云端不跑）：运营通知发送核心（V1.3.4 Story 6.2 · AC1 / AC3 / AC5）。
 *
 * <p>共享测试库里有大量历史用户，故人群断言一律是「我造的人在 / 不在结果里」，真正发送一律用
 * {@code IDS} 模式限定收件人（不对全库发 ALL）。扫描在本基类上下文里不自动跑
 * （{@code petgo.ops-notify.scan-initial-delay-ms}），本类直接调 {@link OpsNotificationScanner#scan()}；
 * 调之前先把库里别的测试遗留的 PENDING 活动置 CANCELED（兜底：6.1 的 L1 已在 {@code @AfterEach} 自行取消），
 * 免得扫描把它们发给全库。IM 为 stub（恒成功）。
 */
class OpsNotificationDispatchIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private OpsAudienceQuery audienceQuery;
    @Autowired
    private OpsNotificationCampaignRepository campaigns;
    @Autowired
    private OpsCampaignTargetDao targets;
    @Autowired
    private OpsNotificationCampaignService campaignService;
    @Autowired
    private OpsNotificationDispatcher dispatcher;
    @Autowired
    private OpsNotificationScanner scanner;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 造数 ----------

    private void sql(String set, long userId) {
        jdbc.update("UPDATE users SET " + set + " WHERE id = ?", userId);
    }

    private void addPet(long ownerId, String petType) {
        jdbc.update("INSERT INTO pet_profiles (owner_id, name, pet_type, card_token, created_at, updated_at) "
                + "VALUES (?, 'Mochi', ?, ?, now(), now())", ownerId, petType, "ops-it-" + SEQ.incrementAndGet());
    }

    private void addOrder(long userId, String status) {
        jdbc.update("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        created_at, updated_at)
                VALUES (?, ?, ?, ?, 100000, 0, 0, 100000,
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110', now(), now())
                """, "opsit" + SEQ.incrementAndGet(), "TOKO-OPS-" + SEQ.incrementAndGet() % 100_000_000,
                userId, status);
    }

    private Set<Long> resolveIds(String json) {
        return audienceQuery.resolve(AudienceSpec.parse(json), 0L).stream()
                .map(OpsRecipient::userId).collect(Collectors.toSet());
    }

    private OpsNotificationCampaign saveIds(List<Long> userIds, Instant scheduledAt) {
        OpsNotificationCampaign c = campaigns.save(OpsNotificationCampaign.create(OpsTokenGenerator.next(),
                "Promo akhir pekan", "Weekend promo", "Isi promo", "Promo body", OpsTargetType.NONE, null,
                AudienceSpec.ids().toJson(), scheduledAt, null));
        targets.insertAll(c.getId(), userIds);
        return c;
    }

    private OpsNotificationCampaign reload(long id) {
        return campaigns.findById(id).orElseThrow();
    }

    private long notificationCount(String campaignToken) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'OPS_ANNOUNCEMENT' "
                + "AND target_ref = ?", Long.class, campaignToken);
    }

    /** 等活动进入终态（扫描 / 事件路径是异步的）。 */
    private OpsNotificationCampaign awaitFinal(long id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            OpsNotificationCampaign c = reload(id);
            if (c.getStatus() == OpsCampaignStatus.DONE || c.getStatus() == OpsCampaignStatus.PARTIAL_FAILED) {
                return c;
            }
            Thread.sleep(100);
        }
        return reload(id);
    }

    private void cancelForeignPending(long... keep) {
        String keepList = java.util.Arrays.stream(keep).mapToObj(Long::toString).collect(Collectors.joining(","));
        jdbc.update("UPDATE ops_notification_campaigns SET status = 'CANCELED', canceled_at = now(), "
                + "updated_at = now() WHERE status = 'PENDING'"
                + (keepList.isEmpty() ? "" : " AND id NOT IN (" + keepList + ")"));
    }

    // ---------- 人群 ----------

    @Test
    @DisplayName("AC1 基础过滤：虚拟号 / 已注销 / 停用状态 / enabled=false / 兽医角色一律不命中")
    void baseFilterExcludesInvalidAccounts() {
        User normal = newUser();
        User virtual = newUser();
        // 造数与生产形态一致：虚拟号 google_sub 必带 virtual: 前缀（否则污染 SyntheticAccountParityTest 的全表护栏）
        sql("account_type = 'VIRTUAL', google_sub = 'virtual:' || google_sub", virtual.getId());
        User deleted = newUser();
        sql("deleted_at = now()", deleted.getId());
        User deactivated = newUser();
        sql("status = 'DEACTIVATED'", deactivated.getId());
        User disabled = newUser();
        sql("enabled = FALSE", disabled.getId());
        User vet = newUser();
        sql("role = 'VET'", vet.getId());

        Set<Long> all = resolveIds("{\"mode\":\"ALL\"}");
        assertThat(all).contains(normal.getId())
                .doesNotContain(virtual.getId(), deleted.getId(), deactivated.getId(), disabled.getId(),
                        vet.getId());
    }

    @Test
    @DisplayName("AC1 FILTER：各条件为「且」；已付单才算下过单；PENDING_PAYMENT 不算")
    void filterConditionsAreAnded() {
        User catNoOrder = newUser();
        addPet(catNoOrder.getId(), "CAT");
        User dogPaid = newUser();
        addPet(dogPaid.getId(), "DOG");
        addOrder(dogPaid.getId(), "SHIPPED");
        User noPetPhone = newUser();
        sql("phone = '081234567890'", noPetPhone.getId());
        User unpaidOnly = newUser();
        addOrder(unpaidOnly.getId(), "PENDING_PAYMENT");

        assertThat(resolveIds("{\"mode\":\"FILTER\",\"species\":[\"CAT\"],\"hasShopOrder\":false}"))
                .contains(catNoOrder.getId())
                .doesNotContain(dogPaid.getId(), noPetPhone.getId(), unpaidOnly.getId());
        assertThat(resolveIds("{\"mode\":\"FILTER\",\"hasPet\":true,\"hasShopOrder\":true}"))
                .contains(dogPaid.getId()).doesNotContain(catNoOrder.getId(), noPetPhone.getId(),
                        unpaidOnly.getId());
        assertThat(resolveIds("{\"mode\":\"FILTER\",\"hasPet\":false,\"hasPhone\":true}"))
                .contains(noPetPhone.getId()).doesNotContain(catNoOrder.getId(), dogPaid.getId(),
                        unpaidOnly.getId());
        Set<Long> withOrder = resolveIds("{\"mode\":\"FILTER\",\"hasShopOrder\":true}");
        assertThat(withOrder).contains(dogPaid.getId()).doesNotContain(unpaidOnly.getId());
    }

    @Test
    @DisplayName("AC1 WIB 边界：created_at 16:59:59Z 属 10-12，17:00:00Z 属 10-13")
    void wibDayBoundary() {
        User before = newUser();
        jdbc.update("UPDATE users SET created_at = ?::timestamptz WHERE id = ?", "2026-10-12T16:59:59Z",
                before.getId());
        User after = newUser();
        jdbc.update("UPDATE users SET created_at = ?::timestamptz WHERE id = ?", "2026-10-12T17:00:00Z",
                after.getId());

        assertThat(resolveIds("{\"mode\":\"FILTER\",\"registeredFrom\":\"2026-10-12\",\"registeredTo\":\"2026-10-12\"}"))
                .contains(before.getId()).doesNotContain(after.getId());
        assertThat(resolveIds("{\"mode\":\"FILTER\",\"registeredFrom\":\"2026-10-13\",\"registeredTo\":\"2026-10-13\"}"))
                .contains(after.getId()).doesNotContain(before.getId());
    }

    @Test
    @DisplayName("AC1 给了活跃条件时 last_active_at 为 NULL 的不命中")
    void activeFilterExcludesNullLastActive() {
        User never = newUser();
        sql("last_active_at = NULL", never.getId());
        User active = newUser();
        jdbc.update("UPDATE users SET last_active_at = ?::timestamptz WHERE id = ?", "2026-10-05T03:00:00Z",
                active.getId());
        assertThat(resolveIds("{\"mode\":\"FILTER\",\"activeFrom\":\"2026-10-01\",\"activeTo\":\"2026-10-10\"}"))
                .contains(active.getId()).doesNotContain(never.getId());
    }

    // ---------- 发送 ----------

    @Test
    @DisplayName("AC3 发送：EN 收件人得 EN 文案、其余 ID；recipient_count = 写入行数；DONE；角标 +1；无效 id 计 skipped")
    void dispatchWritesLocalizedRowsAndCounts() throws InterruptedException {
        User en = newUser();
        sql("locale = 'en'", en.getId());
        User id = newUser();
        User deleted = newUser();
        sql("deleted_at = now()", deleted.getId());
        String badgeKey = NotificationService.UNREAD_KEY_PREFIX + en.getId();
        redis.delete(badgeKey);

        OpsNotificationCampaign c = saveIds(List.of(en.getId(), id.getId(), deleted.getId(), 987_654_321_012L),
                null);
        dispatcher.dispatch(c.getId());
        OpsNotificationCampaign done = awaitFinal(c.getId());

        assertThat(done.getStatus()).isEqualTo(OpsCampaignStatus.DONE);
        assertThat(done.getRecipientCount()).isEqualTo(2);
        assertThat(done.getSkippedCount()).isEqualTo(2);
        assertThat(done.getFailedCount()).isZero();
        assertThat(done.getFinishedAt()).isNotNull();
        assertThat(notificationCount(c.getPublicToken())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT title FROM notifications WHERE recipient_user_id = ? "
                + "AND target_ref = ?", String.class, en.getId(), c.getPublicToken())).isEqualTo("Weekend promo");
        assertThat(jdbc.queryForObject("SELECT title FROM notifications WHERE recipient_user_id = ? "
                + "AND target_ref = ?", String.class, id.getId(), c.getPublicToken()))
                .isEqualTo("Promo akhir pekan");
        assertThat(redis.opsForValue().get(badgeKey)).isEqualTo("1");
    }

    @Test
    @DisplayName("AC3 并发：两个线程同时 dispatch 同一活动 → 通知行数 = 人数（不翻倍）")
    void concurrentDispatchClaimsOnce() throws Exception {
        List<Long> ids = List.of(newUser().getId(), newUser().getId(), newUser().getId());
        OpsNotificationCampaign c = saveIds(ids, null);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> {
                start.await();
                dispatcher.dispatch(c.getId());
                return null;
            });
            Future<?> b = pool.submit(() -> {
                start.await();
                dispatcher.dispatch(c.getId());
                return null;
            });
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(awaitFinal(c.getId()).getStatus()).isEqualTo(OpsCampaignStatus.DONE);
        assertThat(notificationCount(c.getPublicToken())).isEqualTo(3L);
    }

    @Test
    @DisplayName("AC2 立即发送：create 提交后经事件异步发送")
    void immediateCreateSendsAfterCommit() throws InterruptedException {
        User u = newUser();
        String token = campaignService.create(new OpsCampaignDraft("Judul", "Title", "Isi", "Body",
                OpsTargetType.NONE, null, AudienceSpec.ids(), List.of(u.getId()), null), adminActorId());
        OpsNotificationCampaign c = campaigns.findByPublicToken(token).orElseThrow();
        assertThat(awaitFinal(c.getId()).getStatus()).isEqualTo(OpsCampaignStatus.DONE);
        assertThat(notificationCount(token)).isEqualTo(1L);
    }

    // ---------- 定时 / 超时 ----------

    @Test
    @DisplayName("AC5 定时：未到点 scan 不发；改到过去 scan 发；CANCELED 永不发")
    void scheduledAndCanceled() throws InterruptedException {
        User u = newUser();
        OpsNotificationCampaign future = saveIds(List.of(u.getId()), Instant.now().plusSeconds(3600));
        OpsNotificationCampaign canceled = saveIds(List.of(u.getId()), Instant.now().plusSeconds(3600));
        campaignService.cancel(canceled.getPublicToken(), adminActorId());

        cancelForeignPending(future.getId());
        scanner.scan();
        Thread.sleep(500);
        assertThat(reload(future.getId()).getStatus()).isEqualTo(OpsCampaignStatus.PENDING);

        jdbc.update("UPDATE ops_notification_campaigns SET scheduled_at = now() - interval '1 minute' "
                + "WHERE id IN (?, ?)", future.getId(), canceled.getId());
        cancelForeignPending(future.getId());
        scanner.scan();
        assertThat(awaitFinal(future.getId()).getStatus()).isEqualTo(OpsCampaignStatus.DONE);
        assertThat(notificationCount(future.getPublicToken())).isEqualTo(1L);
        assertThat(reload(canceled.getId()).getStatus()).isEqualTo(OpsCampaignStatus.CANCELED);
        assertThat(notificationCount(canceled.getPublicToken())).isZero();
    }

    @Test
    @DisplayName("AC5 超时：SENDING 且 16 分钟无进展 → scan 后 PARTIAL_FAILED，且不再被发送")
    void stalledSendingMarkedPartialFailed() throws InterruptedException {
        User u = newUser();
        OpsNotificationCampaign c = saveIds(List.of(u.getId()), null);
        jdbc.update("UPDATE ops_notification_campaigns SET status = 'SENDING', started_at = now() - interval "
                + "'20 minutes', last_progress_at = now() - interval '16 minutes' WHERE id = ?", c.getId());

        cancelForeignPending();
        scanner.scan();
        Thread.sleep(500);

        OpsNotificationCampaign after = reload(c.getId());
        assertThat(after.getStatus()).isEqualTo(OpsCampaignStatus.PARTIAL_FAILED);
        assertThat(after.getFinishedAt()).isNotNull();
        dispatcher.dispatch(c.getId()); // 不是 PENDING，抢占失败
        assertThat(notificationCount(c.getPublicToken())).isZero();
    }
}

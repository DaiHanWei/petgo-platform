package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.repository.IdCardHdPurchaseRepository;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1 集成：Story 6.3 身份证高清图付费下载（dev profile）。上下文启动验 Flyway V75 + validate
 * （id_card_hd_purchases 契约）。核心：PawCoin 同步扣费解锁落库、二次购买不重复扣、余额不足回滚、
 * QRIS 建意图到账建购买、回调重放幂等、hdUnlocked 标志、无档案 404。
 */
class IdCardHdIntegrationTest extends ApiIntegrationTest {

    private static final long PRICE = 5_000L; // petgo.id-hd.download-price 默认

    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private IdCardHdPurchaseRepository purchases;
    @Autowired
    private PaymentIntentService paymentIntents;

    private void createProfile(long userId) throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Mochi","petType":"CAT","breed":"British","birthday":"2022-01-01"}
                                """))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions purchase(long userId, String channel)
            throws Exception {
        return mvc.perform(post("/api/v1/pet-profiles/me/id-card/hd-download")
                .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"" + channel + "\"}"));
    }

    @Test
    void pawCoinPurchaseDebitsAndUnlocksIdempotent() throws Exception {
        User u = newUser();
        createProfile(u.getId());
        wallet.credit(u.getId(), 20_000L, PawCoinTxnType.TOPUP, "TOPUP", null,
                "seed-" + SEQ.incrementAndGet());
        long before = wallet.balanceOf(u.getId());

        purchase(u.getId(), "PAWCOIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(true));

        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - PRICE);
        assertThat(purchases.existsByUserId(u.getId())).isTrue();

        // GET id-card 反映已解锁
        mvc.perform(get("/api/v1/pet-profiles/me/id-card")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hdUnlocked").value(true));

        // 二次购买短路，不再扣费（余额只减一次）。
        purchase(u.getId(), "PAWCOIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(true));
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - PRICE);
    }

    @Test
    void pawCoinInsufficientBalanceRollsBack() throws Exception {
        User u = newUser();
        createProfile(u.getId()); // 余额 0
        purchase(u.getId(), "PAWCOIN").andExpect(status().isConflict());
        assertThat(purchases.existsByUserId(u.getId())).isFalse(); // 整事务回滚，未建购买
    }

    @Test
    void qrisCreatesIntentThenPaidUnlocksIdempotent() throws Exception {
        User u = newUser();
        createProfile(u.getId());

        var res = purchase(u.getId(), "QRIS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(false))
                .andExpect(jsonPath("$.payment.token").exists())
                .andReturn();
        String intentToken =
                json.readTree(res.getResponse().getContentAsString()).path("payment").path("token").asText();
        assertThat(purchases.existsByUserId(u.getId())).isFalse(); // 未支付未解锁

        // 到账：applyCallback PAID → PaymentIntentPaidEvent → IdHdPaidHandler 同事务建购买。
        paymentIntents.applyCallback(new PaymentCallback(
                intentToken, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID, Map.of()));
        assertThat(purchases.existsByUserId(u.getId())).isTrue();

        // 回调重放幂等：再次 applyCallback 不双建（意图已终态直接返回）。
        paymentIntents.applyCallback(new PaymentCallback(
                intentToken, "gw-dup", GatewayStatus.PAID, Map.of()));
        assertThat(purchases.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void purchaseWithoutProfileIs404() throws Exception {
        User u = newUser();
        purchase(u.getId(), "PAWCOIN").andExpect(status().isNotFound());
    }

    /**
     * 🔴 2026-09-25 code review #3：按卡 QRIS 的<b>未付 / 网关失败</b> attempt 行不得被当成「已购买」。
     *
     * <p>attempt 行在出码前就写了；旧口径 {@code existsByUserId} 只看「有没有行」，
     * 于是没付钱的用户在旧单卡端点上被判成已解锁。
     */
    @Test
    void unpaidCardAttemptRowIsNotAPurchase() {
        User u = newUser();
        var intent = paymentIntents.createIntent(u.getId(), com.tailtopia.pay.domain.PaymentPurpose.ID_HD,
                com.tailtopia.pay.domain.PayChannel.QRIS, PRICE, "IDR", "id-hd-card-test:" + SEQ.incrementAndGet());
        long intentId = paymentIntents.findByToken(intent.token()).orElseThrow().getId();
        purchases.save(com.tailtopia.profile.domain.IdCardHdPurchase.of(
                u.getId(), null, null, com.tailtopia.pay.domain.PayChannel.QRIS, intentId));

        assertThat(purchases.existsByUserId(u.getId())).as("有 attempt 行").isTrue();
        assertThat(purchases.existsPaidByUserId(u.getId())).as("🔴 但没付钱，不算已购买").isFalse();

        paymentIntents.failChargeAttempt(intent.token());   // 网关下单失败
        assertThat(purchases.existsPaidByUserId(u.getId())).isFalse();
    }

    // ---- V1.3.4 ktp Story 2.1（AD-2 / D-8）：按卡端点写 hd_unlocked_at，首次解锁才写 ----

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    private com.tailtopia.profile.repository.IdCardRepository idCards;
    @Autowired
    private com.tailtopia.pay.repository.PaymentIntentRepository intents;

    private long createCard(long userId) throws Exception {
        String body = mvc.perform(post("/api/v1/pet-profiles/me/id-cards")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Mochi","petType":"CAT","breed":"British","birthday":"2022-01-01",
                                 "gender":"FEMALE","cardType":"STUDENT","cardVariant":"BINTANG"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions purchaseCard(long userId, long cardId, String channel)
            throws Exception {
        return mvc.perform(post("/api/v1/pet-profiles/me/id-cards/" + cardId + "/hd-download")
                .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"" + channel + "\"}"));
    }

    private java.time.Instant unlockedAt(long cardId) {
        return idCards.findById(cardId).orElseThrow().getHdUnlockedAt();
    }

    private String qrisToken(long userId, long cardId) throws Exception {
        var res = purchaseCard(userId, cardId, "QRIS").andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(false)).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).path("payment").path("token").asText();
    }

    @Test
    void cardPawcoinUnlockWritesUnlockTime() throws Exception {
        User u = newUser();
        long cardId = createCard(u.getId());
        wallet.credit(u.getId(), 20_000L, PawCoinTxnType.TOPUP, "TOPUP", null, "seed-" + SEQ.incrementAndGet());
        assertThat(unlockedAt(cardId)).isNull();

        java.time.Instant before = java.time.Instant.now();
        purchaseCard(u.getId(), cardId, "PAWCOIN").andExpect(status().isOk()).andExpect(jsonPath("$.unlocked").value(true));
        java.time.Instant after = java.time.Instant.now();

        assertThat(unlockedAt(cardId)).isBetween(before.minusMillis(5), after.plusMillis(5));
    }

    @Test
    void cardQrisPaidWritesArrivalTimeAfterOrderTime() throws Exception {
        User u = newUser();
        long cardId = createCard(u.getId());
        String token = qrisToken(u.getId(), cardId);
        java.sql.Timestamp ordered = jdbc.queryForObject(
                "SELECT purchased_at FROM id_card_hd_purchases WHERE card_id = ?", java.sql.Timestamp.class, cardId);
        assertThat(unlockedAt(cardId)).as("下单不算解锁").isNull();

        Thread.sleep(20);
        paymentIntents.applyCallback(new PaymentCallback(token, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID, Map.of()));

        java.time.Instant at = unlockedAt(cardId);
        assertThat(at).as("到账时刻晚于 QRIS 下单时刻").isAfter(ordered.toInstant());

        // 回调重放（意图已终态）不改写。
        paymentIntents.applyCallback(new PaymentCallback(token, "gw-dup", GatewayStatus.PAID, Map.of()));
        assertThat(unlockedAt(cardId)).isEqualTo(at);
    }

    @Test
    void pawcoinThenEarlierQrisArrivalKeepsFirstUnlockTime() throws Exception {
        User u = newUser();
        long cardId = createCard(u.getId());
        String token = qrisToken(u.getId(), cardId); // 先下 QRIS 单未付
        wallet.credit(u.getId(), 20_000L, PawCoinTxnType.TOPUP, "TOPUP", null, "seed-" + SEQ.incrementAndGet());
        purchaseCard(u.getId(), cardId, "PAWCOIN").andExpect(jsonPath("$.unlocked").value(true));
        java.time.Instant first = unlockedAt(cardId);
        assertThat(first).isNotNull();

        Thread.sleep(20);
        // 那张 QRIS 单之后到账：completeCardByIntent 对已解锁卡再 markHdUnlocked，时间不变。
        paymentIntents.applyCallback(new PaymentCallback(token, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID, Map.of()));
        assertThat(unlockedAt(cardId)).isEqualTo(first);
    }

    /** 迁移里的回填 UPDATE 原文（加上 user_id 限定，避免改到同库其他用例的数据）。 */
    private static String backfillSql() throws java.io.IOException {
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of("src/main/resources/db/migration"))) {
            java.nio.file.Path f = files.filter(p -> p.getFileName().toString().endsWith("__id_cards_hd_unlocked_at.sql"))
                    .findFirst().orElseThrow();
            String sql = java.nio.file.Files.readString(f);
            int start = sql.indexOf("UPDATE id_cards c");
            int end = sql.indexOf("WHERE c.hd_unlocked;", start);
            return sql.substring(start, end) + "WHERE c.hd_unlocked AND c.user_id = ?";
        }
    }

    private long rawCard(long userId, boolean unlocked, java.time.Instant createdAt) {
        return jdbc.queryForObject("INSERT INTO id_cards (user_id, serial_id, name, hd_unlocked, created_at) "
                        + "VALUES (?, ?, 'Mochi', ?, ?) RETURNING id", Long.class, userId,
                // serial 全局唯一：放到远高于真实号池的区间，跨次运行也不撞号。
                9_000_000_000L + SEQ.incrementAndGet() % 1_000_000_000L, unlocked, java.sql.Timestamp.from(createdAt));
    }

    private void row(long userId, long cardId, String channel, Long intentId, java.time.Instant at) {
        jdbc.update("INSERT INTO id_card_hd_purchases (user_id, card_id, pay_channel, payment_intent_id, purchased_at) "
                + "VALUES (?, ?, ?, ?, ?)", userId, cardId, channel, intentId, java.sql.Timestamp.from(at));
    }

    private long intent(long userId, boolean paid, java.time.Instant updatedAt) {
        var p = com.tailtopia.pay.domain.PaymentIntent.create(userId, com.tailtopia.pay.domain.PaymentPurpose.ID_HD,
                com.tailtopia.pay.domain.PayChannel.QRIS, PRICE, "IDR",
                ("t" + SEQ.incrementAndGet() + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx").substring(0, 32));
        if (paid) {
            p.markPaid(Map.of());
        }
        p = intents.saveAndFlush(p);
        jdbc.update("UPDATE payment_intents SET updated_at = ? WHERE id = ?", java.sql.Timestamp.from(updatedAt), p.getId());
        return p.getId();
    }

    @Test
    void migrationBackfillFollowsD8() throws Exception {
        User u = newUser();
        java.time.Instant t0 = java.time.Instant.parse("2026-09-10T03:00:00Z");
        long coin = rawCard(u.getId(), true, t0);
        row(u.getId(), coin, "PAWCOIN", null, t0.plusSeconds(3600));
        long qris = rawCard(u.getId(), true, t0);
        long paidIntent = intent(u.getId(), true, t0.plusSeconds(7200));
        row(u.getId(), qris, "QRIS", paidIntent, t0.plusSeconds(60)); // 下单时间不算
        long multi = rawCard(u.getId(), true, t0);
        row(u.getId(), multi, "QRIS", intent(u.getId(), false, t0.plusSeconds(10)), t0.plusSeconds(10)); // 未付不算
        row(u.getId(), multi, "PAWCOIN", null, t0.plusSeconds(500));
        row(u.getId(), multi, "PAWCOIN", null, t0.plusSeconds(900)); // 一卡多行取最早
        long legacy = rawCard(u.getId(), true, t0.plusSeconds(42)); // 无购买行 → created_at
        long locked = rawCard(u.getId(), false, t0);
        row(u.getId(), locked, "QRIS", intent(u.getId(), false, t0), t0); // 未解锁卡即便有购买行也不回填

        jdbc.update(backfillSql(), u.getId());

        java.util.function.LongFunction<java.time.Instant> at = id -> {
            java.sql.Timestamp ts = jdbc.queryForObject("SELECT hd_unlocked_at FROM id_cards WHERE id = ?",
                    java.sql.Timestamp.class, id);
            return ts == null ? null : ts.toInstant();
        };
        assertThat(at.apply(coin)).isEqualTo(t0.plusSeconds(3600));
        assertThat(at.apply(qris)).isEqualTo(t0.plusSeconds(7200));
        assertThat(at.apply(multi)).isEqualTo(t0.plusSeconds(500));
        assertThat(at.apply(legacy)).isEqualTo(t0.plusSeconds(42));
        assertThat(at.apply(locked)).isNull();
    }
}

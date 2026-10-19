package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import com.tailtopia.auth.domain.User;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.pay.service.PaymentReconciliationService;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1（需 postgres + redis，云端不跑）：V1.4.0 multi-pet Story 2.2 · AC1 / AC2 / AC4 – AC8 / AC10 ——
 * 迁移 1601 / 1602 空库迁移 + {@code validate}；PawCoin 租用 / 租满 409；QRIS 回调与对账轮询并发只发一次；失败 / 过期不增名额；
 * {@code statusOf} 懒过期后购买状态自愈；续租两条一次付款各 +30 天；缺 {@code Idempotency-Key} 422；他人 token 404；
 * 发放后 reconcile 恢复只读宠物。
 *
 * <p>等级用 {@link MockitoBean} 的 {@link GrowthQueryService} 设定；网关用 {@link MockitoBean} 的 {@link PaymentGateway}
 * （{@code createCharge} 回 {@code gw-<intentToken>}，{@code queryCharge} 回 PAID —— 模拟对账轮询拿到到账）。
 */
class PetSlotRentalIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/api/v1/pet-slots/me";

    @MockitoBean
    private GrowthQueryService growth;
    @MockitoBean
    private PaymentGateway gateway;
    /** code review #1：用于让 reconcile 抛错，验证到账不回滚。 */
    @MockitoSpyBean
    private PetSlotStateService stateSpy;

    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private PaymentReconciliationService reconciliation;
    @Autowired
    private JdbcTemplate jdbc;

    /** 兽医 token 经 BannedVetFilter 查 vet 行：不存在 / 非 ACTIVE → 401。要验「角色拦截 403」必须先造真兽医，不能拿任意 id 当 vetId。 */
    @Autowired
    private VetTestSupport vets;

    @BeforeEach
    void setUp() {
        when(growth.getLevel(anyLong())).thenReturn(GrowthLevel.of(1, GrowthTier.NEWBIE));
        when(gateway.createCharge(any(ChargeRequest.class))).thenAnswer(inv -> {
            ChargeRequest r = inv.getArgument(0);
            return new ChargeResult("gw-" + r.orderId(), "QR-" + r.orderId(), Map.of());
        });
        when(gateway.queryCharge(anyString())).thenAnswer(inv -> {
            String ref = inv.getArgument(0);
            return Optional.of(new PaymentCallback(ref.substring(3), ref, GatewayStatus.PAID, Map.of()));
        });
    }

    private void fund(long uid) {
        wallet.credit(uid, 100_000L, PawCoinTxnType.TOPUP, "TEST", null, "ps-topup:" + uid + ":" + SEQ.incrementAndGet());
    }

    private ResultActions rent(long uid, int qty, String channel, String key) throws Exception {
        var req = post(BASE + "/rentals").header(HttpHeaders.AUTHORIZATION, userBearer(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":" + qty + ",\"channel\":\"" + channel + "\"}");
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    private ResultActions renew(long uid, List<String> tokens, String channel, String key) throws Exception {
        return mvc.perform(post(BASE + "/renewals").header(HttpHeaders.AUTHORIZATION, userBearer(uid))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("rentalTokens", tokens, "channel", channel))));
    }

    private JsonNode overview(long uid) throws Exception {
        String body = mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, userBearer(uid)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private long rentalCount(long uid) {
        return jdbc.queryForObject("SELECT count(*) FROM pet_slot_rentals WHERE user_id = ?", Long.class, uid);
    }

    private String purchaseStatus(String purchaseToken) {
        return jdbc.queryForObject("SELECT status FROM pet_slot_purchases WHERE public_token = ?", String.class,
                purchaseToken);
    }

    @Test
    void migrationShape() {
        assertThat(jdbc.queryForObject("SELECT pet_slot_monthly_price FROM pricing_config WHERE id = 1", Long.class))
                .isGreaterThanOrEqualTo(100L);
        for (String c : List.of("ck_pricing_pet_slot_monthly_min", "ck_pet_slot_purchases_kind",
                "ck_pet_slot_purchases_status", "ck_pet_slot_rentals_status", "ck_pet_slot_rentals_window")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = ?", Integer.class, c))
                    .as(c).isEqualTo(1);
        }
        for (String i : List.of("ix_pet_slot_rentals_status_expires", "ix_pet_slot_rentals_user_status",
                "ix_pet_slot_purchases_user_created")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class, i))
                    .as(i).isEqualTo(1);
        }
    }

    @Test
    void pawcoinRentTwoThenRentableTwoThenFullIs409() throws Exception {
        User u = newUser();
        fund(u.getId());
        long before = wallet.balanceOf(u.getId());
        rent(u.getId(), 2, "PAWCOIN", "k1").andExpect(status().isOk()).andExpect(jsonPath("$.unlocked").value(true));
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - 10_000L);
        JsonNode o = overview(u.getId());
        assertThat(o.get("total").asInt()).isEqualTo(3);
        assertThat(o.get("rentalSlots").asInt()).isEqualTo(2);
        assertThat(o.get("rentable").asInt()).isEqualTo(2);
        assertThat(o.get("rentals").size()).isEqualTo(2);
        assertThat(o.get("rentals").get(0).get("index").asInt()).isEqualTo(1);
        assertThat(o.get("rentals").get(0).get("daysLeft").asInt()).isEqualTo(30);

        // 同键重放：同一结果，不再扣币
        rent(u.getId(), 2, "PAWCOIN", "k1").andExpect(status().isOk());
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - 10_000L);
        assertThat(rentalCount(u.getId())).isEqualTo(2);

        rent(u.getId(), 3, "PAWCOIN", "k2").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("pet-slot-rent-unavailable")))
                .andExpect(jsonPath("$.rentable").value(2));
        rent(u.getId(), 2, "PAWCOIN", "k3").andExpect(status().isOk());
        assertThat(overview(u.getId()).get("total").asInt()).isEqualTo(5);
        rent(u.getId(), 1, "PAWCOIN", "k4").andExpect(status().isConflict())
                .andExpect(jsonPath("$.rentable").value(0));
        // PawCoin 流水 refType = PET_SLOT_RENT（① 成长值口径）
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pawcoin_transactions WHERE user_id = ? AND ref_type = 'PET_SLOT_RENT'",
                Long.class, u.getId())).isEqualTo(2L);
    }

    @Test
    void missingIdempotencyKeyIs422AndVetIs403() throws Exception {
        User u = newUser();
        long vetId = vets.newActiveVet("pet-slot-rental-it").getId();
        rent(u.getId(), 1, "PAWCOIN", null).andExpect(status().isUnprocessableEntity());
        mvc.perform(post(BASE + "/rentals").header(HttpHeaders.AUTHORIZATION, vetBearer(vetId))
                .header("Idempotency-Key", "x").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1,\"channel\":\"PAWCOIN\"}")).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/purchases/abc").header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isForbidden());
        rent(u.getId(), 1, "MIXED", "m").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void insufficientPawcoinLeavesNothing() throws Exception {
        User u = newUser();
        rent(u.getId(), 1, "PAWCOIN", "poor").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("pawcoin-insufficient")));
        assertThat(rentalCount(u.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_slot_purchases WHERE user_id = ?", Long.class,
                u.getId())).isZero();
    }

    @Test
    void qrisCallbackAndReconciliationConcurrentGrantOnce() throws Exception {
        User u = newUser();
        JsonNode r = body(rent(u.getId(), 2, "QRIS", "q1").andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(false)));
        String intentToken = r.get("payment").get("token").asString();
        String purchaseToken = r.get("purchaseToken").asString();
        assertThat(r.get("payment").get("amount").asLong()).isEqualTo(10_000L);
        assertThat(rentalCount(u.getId())).isZero();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cb = pool.submit(() -> {
                start.await();
                paymentIntents.applyCallback(new PaymentCallback(intentToken, "gw-" + intentToken, GatewayStatus.PAID,
                        Map.of()));
                return null;
            });
            Future<?> poll = pool.submit(() -> {
                start.await();
                reconciliation.reconcile(intentToken);
                return null;
            });
            start.countDown();
            try {
                cb.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 并发下一方可能撞 gateway_ref 唯一 / @Version —— 只要最终只生效一次
            }
            try {
                poll.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 同上
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(rentalCount(u.getId())).isEqualTo(2);
        assertThat(purchaseStatus(purchaseToken)).isEqualTo("PAID");
        mvc.perform(get(BASE + "/purchases/" + purchaseToken).header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
        assertThat(overview(u.getId()).get("total").asInt()).isEqualTo(3);
    }

    @Test
    void qrisFailedAndExpiredDoNotAddSlots() throws Exception {
        User u = newUser();
        JsonNode a = body(rent(u.getId(), 1, "QRIS", "f1").andExpect(status().isOk()));
        paymentIntents.applyCallback(new PaymentCallback(a.get("payment").get("token").asString(),
                "gw-" + a.get("payment").get("token").asString(), GatewayStatus.FAILED, Map.of()));
        assertThat(purchaseStatus(a.get("purchaseToken").asString())).isEqualTo("FAILED");

        JsonNode b = body(rent(u.getId(), 1, "QRIS", "f2").andExpect(status().isOk()));
        jdbc.update("UPDATE payment_intents SET expires_at = now() - interval '1 minute' WHERE public_token = ?",
                b.get("payment").get("token").asString());
        paymentIntents.expireOverduePending(100);
        assertThat(purchaseStatus(b.get("purchaseToken").asString())).isEqualTo("EXPIRED");

        assertThat(rentalCount(u.getId())).isZero();
        assertThat(overview(u.getId()).get("total").asInt()).isEqualTo(1);
    }

    /**
     * stag 复验：网关出码失败（stag GemPay 恒 P04）→ 不再 500：200 + 无载荷，意图 / 购买行 FAILED 留档；
     * App 收到 200 无载荷时保留键也无妨 —— 同键第 2 次点击即另起新意图（新 request_id）、新行，真正重下。
     */
    @Test
    void qrisGatewayChargeFailureIsNot500AndSameKeyRetryReordersWithNewIntent() throws Exception {
        User u = newUser();
        when(gateway.createCharge(any(ChargeRequest.class)))
                .thenThrow(new com.tailtopia.shared.pay.PayException("支付网关收款失败"))
                .thenAnswer(inv -> {
                    ChargeRequest r = inv.getArgument(0);
                    return new ChargeResult("gw-" + r.orderId(), "QR-" + r.orderId(), Map.of());
                });

        JsonNode a = body(rent(u.getId(), 1, "QRIS", "gwf").andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(false))
                .andExpect(jsonPath("$.payload").doesNotExist())
                .andExpect(jsonPath("$.payment").doesNotExist()));
        String failedPurchase = a.get("purchaseToken").asString();
        assertThat(purchaseStatus(failedPurchase)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT i.status FROM payment_intents i JOIN pet_slot_purchases p ON p.payment_intent_id = i.id
                WHERE p.public_token = ?""", String.class, failedPurchase)).isEqualTo("FAILED");

        JsonNode b = body(rent(u.getId(), 1, "QRIS", "gwf").andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(false)));
        String retryIntent = b.get("payment").get("token").asString();
        assertThat(b.get("payload").asString()).isEqualTo("QR-" + retryIntent);
        assertThat(b.get("purchaseToken").asString()).isNotEqualTo(failedPurchase);
        assertThat(purchaseStatus(b.get("purchaseToken").asString())).isEqualTo("PENDING");
        assertThat(purchaseStatus(failedPurchase)).isEqualTo("FAILED");
        assertThat(rentalCount(u.getId())).isZero();
    }

    @Test
    void lazyExpiryViaStatusEndpointSelfHeals() throws Exception {
        User u = newUser();
        JsonNode a = body(rent(u.getId(), 1, "QRIS", "lz").andExpect(status().isOk()));
        jdbc.update("UPDATE payment_intents SET expires_at = now() - interval '1 minute' WHERE public_token = ?",
                a.get("payment").get("token").asString());
        mvc.perform(get(BASE + "/purchases/" + a.get("purchaseToken").asString())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(purchaseStatus(a.get("purchaseToken").asString())).isEqualTo("EXPIRED");
    }

    @Test
    void renewTwoRentalsInOnePaymentEachPlusThirtyDays() throws Exception {
        User u = newUser();
        fund(u.getId());
        rent(u.getId(), 2, "PAWCOIN", "r0").andExpect(status().isOk());
        JsonNode rentals = overview(u.getId()).get("rentals");
        String t1 = rentals.get(0).get("token").asString();
        String t2 = rentals.get(1).get("token").asString();
        Instant e1 = Instant.parse(rentals.get(0).get("expiresAt").asString());
        jdbc.update("UPDATE pet_slot_rentals SET reminded_at = now() WHERE public_token IN (?, ?)", t1, t2);
        long before = wallet.balanceOf(u.getId());

        renew(u.getId(), List.of(t1, t2), "PAWCOIN", "rn1").andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(true));
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - 10_000L);
        JsonNode after = overview(u.getId()).get("rentals");
        assertThat(Instant.parse(after.get(0).get("expiresAt").asString())).isEqualTo(e1.plus(java.time.Duration.ofDays(30)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_slot_rentals WHERE public_token IN (?, ?) AND reminded_at IS NULL",
                Long.class, t1, t2)).isEqualTo(2L);
        assertThat(rentalCount(u.getId())).isEqualTo(2);
    }

    @Test
    void othersTokensAre404() throws Exception {
        User owner = newUser();
        fund(owner.getId());
        String purchaseToken = body(rent(owner.getId(), 1, "PAWCOIN", "o1").andExpect(status().isOk()))
                .get("purchaseToken").asString();
        String rentalToken = overview(owner.getId()).get("rentals").get(0).get("token").asString();
        User other = newUser();
        fund(other.getId());
        mvc.perform(get(BASE + "/purchases/" + purchaseToken).header(HttpHeaders.AUTHORIZATION, userBearer(other.getId())))
                .andExpect(status().isNotFound());
        renew(other.getId(), List.of(rentalToken), "PAWCOIN", "o2").andExpect(status().isNotFound());
    }

    @Test
    void expiredRentalCannotBeRenewed() throws Exception {
        User u = newUser();
        fund(u.getId());
        rent(u.getId(), 1, "PAWCOIN", "e0").andExpect(status().isOk());
        String t = overview(u.getId()).get("rentals").get(0).get("token").asString();
        jdbc.update("UPDATE pet_slot_rentals SET status = 'EXPIRED', expired_at = now() WHERE public_token = ?", t);
        renew(u.getId(), List.of(t), "PAWCOIN", "e1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("pet-slot-renew-invalid")));
    }

    @Test
    void rentRestoresReadOnlyPetViaReconcile() throws Exception {
        User u = newUser();
        fund(u.getId());
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        MultiPetDbFixture.insertPet(jdbc, u.getId(), "A", "CAT", t0);
        long b = MultiPetDbFixture.insertPet(jdbc, u.getId(), "B", "CAT", t0.plusSeconds(60));
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", b);
        assertThat(overview(u.getId()).get("shortBy").asInt()).isEqualTo(1);
        assertThat(overview(u.getId()).get("readOnlyCount").asInt()).isEqualTo(1);
        rent(u.getId(), 1, "PAWCOIN", "rs").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM pet_profiles WHERE id = ?", String.class, b))
                .isEqualTo("ACTIVE");
        assertThat(overview(u.getId()).get("readOnlyCount").asInt()).isZero();
    }

    /** code review #1：到账后 reconcile 抛错 → 意图仍 PAID、名额已发、购买行 PAID（reconcile 在提交后另开事务）。 */
    @Test
    void reconcileFailureAfterPaidDoesNotRollBackPayment() throws Exception {
        User u = newUser();
        org.mockito.Mockito.doThrow(new IllegalStateException("reconcile boom")).when(stateSpy)
                .reconcile(org.mockito.ArgumentMatchers.eq(u.getId()), org.mockito.ArgumentMatchers.any());
        JsonNode r = body(rent(u.getId(), 2, "QRIS", "rb").andExpect(status().isOk()));
        String intentToken = r.get("payment").get("token").asString();
        paymentIntents.applyCallback(new PaymentCallback(intentToken, "gw-" + intentToken, GatewayStatus.PAID, Map.of()));
        assertThat(jdbc.queryForObject("SELECT status FROM payment_intents WHERE public_token = ?", String.class,
                intentToken)).isEqualTo("PAID");
        assertThat(purchaseStatus(r.get("purchaseToken").asString())).isEqualTo("PAID");
        assertThat(rentalCount(u.getId())).isEqualTo(2);
        org.mockito.Mockito.verify(stateSpy).reconcile(u.getId(), PetSlotTrigger.RENTAL_ADDED);
    }

    /** code review #1：发放失败（保存点回滚）→ 意图 PAID、购买行 ORPHAN_PAID、无名额。 */
    @Test
    void grantFailureLeavesIntentPaidAndPurchaseOrphan() throws Exception {
        User u = newUser();
        JsonNode r = body(rent(u.getId(), 1, "QRIS", "orph").andExpect(status().isOk()));
        String intentToken = r.get("payment").get("token").asString();
        // 让发放的 INSERT 失败：临时把来源 CHECK 收紧到不接受 RENT（仅本测试，finally 还原）。
        // DROP + ADD 合成一条语句（原子），避免 ADD 失败时约束被删着漏到后续测试。
        jdbc.execute("ALTER TABLE pet_slot_rentals DROP CONSTRAINT ck_pet_slot_rentals_source,"
                + " ADD CONSTRAINT ck_pet_slot_rentals_source CHECK (source IN ('ADMIN_GRANT')) NOT VALID");
        try {
            paymentIntents.applyCallback(new PaymentCallback(intentToken, "gw-" + intentToken, GatewayStatus.PAID,
                    Map.of()));
        } finally {
            jdbc.execute("ALTER TABLE pet_slot_rentals DROP CONSTRAINT ck_pet_slot_rentals_source,"
                    + " ADD CONSTRAINT ck_pet_slot_rentals_source CHECK (source IN ('RENT', 'ADMIN_GRANT'))");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM payment_intents WHERE public_token = ?", String.class,
                intentToken)).isEqualTo("PAID");
        assertThat(purchaseStatus(r.get("purchaseToken").asString())).isEqualTo("ORPHAN_PAID");
        assertThat(rentalCount(u.getId())).isZero();
    }

    /** code review #2：QRIS 租 4 未付 → PawCoin 再租被 409；他键 QRIS 同样被拦。 */
    @Test
    void openQrisOrderBlocksFurtherRents() throws Exception {
        User u = newUser();
        fund(u.getId());
        rent(u.getId(), 4, "QRIS", "big").andExpect(status().isOk());
        rent(u.getId(), 1, "PAWCOIN", "pc").andExpect(status().isConflict()).andExpect(jsonPath("$.rentable").value(0));
        rent(u.getId(), 1, "QRIS", "q-other").andExpect(status().isConflict());
        // 同键重放仍出同一张码
        rent(u.getId(), 4, "QRIS", "big").andExpect(status().isOk());
    }

    /** code review #2：到账时会超 5 → 不发名额、购买行 OVERPAID，意图仍 PAID。 */
    @Test
    void paidOverCapIsOverpaidWithoutUselessRentals() throws Exception {
        User u = newUser();
        JsonNode r = body(rent(u.getId(), 4, "QRIS", "oc").andExpect(status().isOk()));
        // 付款窗内名额被别的途径占满（模拟运营补发 4 个，4.3）
        long purchaseId = jdbc.queryForObject("SELECT id FROM pet_slot_purchases WHERE public_token = ?", Long.class,
                r.get("purchaseToken").asString());
        for (int i = 0; i < 4; i++) {
            jdbc.update("INSERT INTO pet_slot_rentals (public_token, user_id, source, starts_at, expires_at, status, purchase_id)"
                    + " VALUES (?, ?, 'ADMIN_GRANT', now(), now() + interval '7 days', 'ACTIVE', ?)",
                    "adm" + SEQ.incrementAndGet(), u.getId(), purchaseId);
        }
        String intentToken = r.get("payment").get("token").asString();
        paymentIntents.applyCallback(new PaymentCallback(intentToken, "gw-" + intentToken, GatewayStatus.PAID, Map.of()));
        assertThat(purchaseStatus(r.get("purchaseToken").asString())).isEqualTo("OVERPAID");
        assertThat(rentalCount(u.getId())).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_intents WHERE public_token = ?", String.class,
                intentToken)).isEqualTo("PAID");
    }

    /** code review #3：同键重开（旧意图过窗）→ 旧行 EXPIRED 且仍挂旧意图，新行带新意图 id。 */
    @Test
    void sameKeyReopenAfterExpiryKeepsOldRowAndInsertsNew() throws Exception {
        User u = newUser();
        JsonNode a = body(rent(u.getId(), 1, "QRIS", "ro").andExpect(status().isOk()));
        String oldIntent = a.get("payment").get("token").asString();
        jdbc.update("UPDATE payment_intents SET expires_at = now() - interval '1 minute' WHERE public_token = ?", oldIntent);
        JsonNode b = body(rent(u.getId(), 1, "QRIS", "ro").andExpect(status().isOk()));
        assertThat(b.get("payment").get("token").asString()).isNotEqualTo(oldIntent);
        assertThat(b.get("purchaseToken").asString()).isNotEqualTo(a.get("purchaseToken").asString());
        assertThat(purchaseStatus(a.get("purchaseToken").asString())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT p.payment_intent_id = i.id FROM pet_slot_purchases p, payment_intents i"
                + " WHERE p.public_token = ? AND i.public_token = ?", Boolean.class,
                a.get("purchaseToken").asString(), oldIntent)).isTrue();
    }
}

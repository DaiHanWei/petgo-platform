package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.support.ApiIntegrationTest;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 postgres + redis，云端不跑）：V1.4.0 multi-pet Story 2.3 · AC2 ~ AC6（照 {@code SipdhExpiryScanIntegrationTest}，
 * 直接调 {@link PetSlotExpiryService#scan}；基类已用 {@code petgo.pet-slot.expiry-scan-cron=-} 关掉定时触发）。
 *
 * <p>造法：3 只宠物 + 名额（默认 1 + PawCoin 月租 2）→ 拨某名额到期 → 扫描后名额 EXPIRED、第 3 只 READ_ONLY、
 * 一行 {@code PET_SLOT_EXPIRED}（id 文案含宠物名）；<b>再扫一次通知仍一行</b>（架构 §5 幂等可重放）。
 * 即将到期一行 {@code PET_SLOT_EXPIRING}、再扫不重复；续租清空 {@code reminded_at} 后新窗口再提醒一次；en 账号英文；
 * 扫描与续租到账并发（两线程，同一把账号锁）不误置 EXPIRED。
 *
 * <p>Mock 定义与 {@code PetSlotRentalIntegrationTest} 完全相同（共用同一个缓存上下文，不多开连接池）。
 */
class PetSlotExpiryScanIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/api/v1/pet-slots/me";

    @MockitoBean
    private GrowthQueryService growth;
    @MockitoBean
    private PaymentGateway gateway;
    @MockitoSpyBean
    private PetSlotStateService stateSpy;

    @Autowired
    private PetSlotExpiryService expiry;
    @Autowired
    private PetSlotLock lock;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private JdbcTemplate jdbc;

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
        wallet.credit(uid, 100_000L, PawCoinTxnType.TOPUP, "TEST", null, "pse-topup:" + uid + ":" + SEQ.incrementAndGet());
    }

    private void rentTwo(long uid) throws Exception {
        mvc.perform(post(BASE + "/rentals").header(HttpHeaders.AUTHORIZATION, userBearer(uid))
                        .header("Idempotency-Key", "pse-" + SEQ.incrementAndGet())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2,\"channel\":\"PAWCOIN\"}"))
                .andExpect(status().isOk());
    }

    private void renew(long uid, String rentalToken) throws Exception {
        mvc.perform(post(BASE + "/renewals").header(HttpHeaders.AUTHORIZATION, userBearer(uid))
                        .header("Idempotency-Key", "pse-rn-" + SEQ.incrementAndGet())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rentalTokens", List.of(rentalToken), "channel", "PAWCOIN"))))
                .andExpect(status().isOk());
    }

    private List<String> rentalTokens(long uid) throws Exception {
        String body = mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, userBearer(uid)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode rentals = json.readTree(body).get("rentals");
        return List.of(rentals.get(0).get("token").asString(), rentals.get(1).get("token").asString());
    }

    /** 把名额拨到 {@code now + offsetSeconds} 到期（starts_at 同步前移，满足 ck_pet_slot_rentals_window）。 */
    private void setExpiry(String rentalToken, long offsetSeconds) {
        jdbc.update("UPDATE pet_slot_rentals SET starts_at = now() + make_interval(secs => ?) - interval '30 days',"
                + " expires_at = now() + make_interval(secs => ?) WHERE public_token = ?",
                (double) offsetSeconds, (double) offsetSeconds, rentalToken);
    }

    private String rentalStatus(String rentalToken) {
        return jdbc.queryForObject("SELECT status FROM pet_slot_rentals WHERE public_token = ?", String.class, rentalToken);
    }

    private String petStatus(long petId) {
        return jdbc.queryForObject("SELECT status FROM pet_profiles WHERE id = ?", String.class, petId);
    }

    private long notices(long uid, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = ?",
                Long.class, uid, type);
    }

    private Map<String, Object> notice(long uid, String type) {
        return jdbc.queryForMap("SELECT title, body, deep_link_type, target_ref FROM notifications"
                + " WHERE recipient_user_id = ? AND type = ? ORDER BY id DESC LIMIT 1", uid, type);
    }

    /** 3 只宠物（A 最早）+ 默认 1 + 月租 2 = 3 名额；返回 [A, B, C] 的 id。 */
    private long[] threePetsThreeSlots(User u) throws Exception {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        long a = MultiPetDbFixture.insertPet(jdbc, u.getId(), "Mochi", "CAT", t0);
        long b = MultiPetDbFixture.insertPet(jdbc, u.getId(), "Kopi", "CAT", t0.plusSeconds(60));
        long c = MultiPetDbFixture.insertPet(jdbc, u.getId(), "Teh", "DOG", t0.plusSeconds(120));
        fund(u.getId());
        rentTwo(u.getId());
        return new long[] {a, b, c};
    }

    @Test
    void expiryDemotesNewestPetAndNotifiesOnceEvenWhenScannedTwice() throws Exception {
        User u = newUser();
        long[] pets = threePetsThreeSlots(u);
        String t1 = rentalTokens(u.getId()).get(0);
        setExpiry(t1, -60);

        expiry.scan(Instant.now());

        assertThat(rentalStatus(t1)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT expired_at IS NOT NULL FROM pet_slot_rentals WHERE public_token = ?",
                Boolean.class, t1)).isTrue();
        assertThat(petStatus(pets[0])).isEqualTo("ACTIVE");
        assertThat(petStatus(pets[1])).isEqualTo("ACTIVE");
        assertThat(petStatus(pets[2])).isEqualTo("READ_ONLY");
        assertThat(notices(u.getId(), "PET_SLOT_EXPIRED")).isEqualTo(1L);
        Map<String, Object> n = notice(u.getId(), "PET_SLOT_EXPIRED");
        assertThat(n.get("title")).isEqualTo("Slot hewan sudah berakhir");
        assertThat(n.get("body")).isEqualTo("Teh sekarang hanya-baca. Perpanjang slot untuk mengaktifkannya lagi.");
        assertThat(n.get("deep_link_type")).isEqualTo("PET_SLOT_EXPIRED");
        assertThat(n.get("target_ref")).isNull();

        // 幂等重放：再扫一次，不重复处理、不重复通知
        expiry.scan(Instant.now());
        assertThat(notices(u.getId(), "PET_SLOT_EXPIRED")).isEqualTo(1L);
        org.mockito.Mockito.verify(stateSpy, org.mockito.Mockito.times(1))
                .reconcile(u.getId(), PetSlotTrigger.RENTAL_EXPIRED);
    }

    @Test
    void expiryWithoutDemotionSendsNothing() throws Exception {
        User u = newUser();
        MultiPetDbFixture.insertPet(jdbc, u.getId(), "Mochi", "CAT", Instant.parse("2026-01-01T00:00:00Z"));
        fund(u.getId());
        rentTwo(u.getId());
        String t1 = rentalTokens(u.getId()).get(0);
        setExpiry(t1, -60);
        expiry.scan(Instant.now());
        assertThat(rentalStatus(t1)).isEqualTo("EXPIRED");
        assertThat(notices(u.getId(), "PET_SLOT_EXPIRED")).isZero();
    }

    @Test
    void expiringRemindsOnceAndAgainAfterRenewal() throws Exception {
        User u = newUser();
        threePetsThreeSlots(u);
        String t2 = rentalTokens(u.getId()).get(1);
        setExpiry(t2, 2 * 86_400L);

        expiry.scan(Instant.now());
        assertThat(notices(u.getId(), "PET_SLOT_EXPIRING")).isEqualTo(1L);
        assertThat(notice(u.getId(), "PET_SLOT_EXPIRING").get("title").toString()).startsWith("Slot hewan berakhir ");
        assertThat(notice(u.getId(), "PET_SLOT_EXPIRING").get("body").toString()).startsWith("Perpanjang sebelum ");
        expiry.scan(Instant.now());
        assertThat(notices(u.getId(), "PET_SLOT_EXPIRING")).isEqualTo(1L);

        renew(u.getId(), t2); // 2.2：+30 天并清空 reminded_at
        assertThat(jdbc.queryForObject("SELECT reminded_at IS NULL FROM pet_slot_rentals WHERE public_token = ?",
                Boolean.class, t2)).isTrue();
        expiry.scan(Instant.now()); // 新到期日不在 3 天窗口
        assertThat(notices(u.getId(), "PET_SLOT_EXPIRING")).isEqualTo(1L);

        setExpiry(t2, 86_400L); // 「时间流逝」到新到期日前 1 天
        expiry.scan(Instant.now());
        assertThat(notices(u.getId(), "PET_SLOT_EXPIRING")).isEqualTo(2L);
        assertThat(notices(u.getId(), "PET_SLOT_EXPIRED")).isZero();
    }

    @Test
    void englishAccountGetsEnglishCopy() throws Exception {
        User u = newUser();
        u.setLocale("en");
        users.save(u);
        long[] pets = threePetsThreeSlots(u);
        List<String> tokens = rentalTokens(u.getId());
        setExpiry(tokens.get(0), -60);
        setExpiry(tokens.get(1), 86_400L);

        expiry.scan(Instant.now());

        assertThat(petStatus(pets[2])).isEqualTo("READ_ONLY");
        assertThat(notice(u.getId(), "PET_SLOT_EXPIRED").get("body"))
                .isEqualTo("Teh is now read-only. Renew the slot to reactivate it.");
        assertThat(notice(u.getId(), "PET_SLOT_EXPIRING").get("title").toString()).startsWith("Pet slot ends ");
    }

    /** AC6：续租到账持有账号锁、把到期日推后；扫描在锁后重读 → 不置 EXPIRED。 */
    @Test
    void concurrentRenewalWinsOverScan() throws Exception {
        User u = newUser();
        long[] pets = threePetsThreeSlots(u);
        String t1 = rentalTokens(u.getId()).get(0);
        setExpiry(t1, -60);
        Instant scanAt = Instant.now();

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<?> renewal = pool.submit(() -> tx.executeWithoutResult(s -> {
                lock.lockUser(u.getId());
                jdbc.update("UPDATE pet_slot_rentals SET expires_at = expires_at + interval '30 days', reminded_at = NULL"
                        + " WHERE public_token = ?", t1);
                locked.countDown();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            expiry.expireForUser(u.getId(), scanAt); // 阻塞在账号锁上，续租提交后重读
            renewal.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rentalStatus(t1)).isEqualTo("ACTIVE");
        assertThat(petStatus(pets[2])).isEqualTo("ACTIVE");
        assertThat(notices(u.getId(), "PET_SLOT_EXPIRED")).isZero();
    }
}

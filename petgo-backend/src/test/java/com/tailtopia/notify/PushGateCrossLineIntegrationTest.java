package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.dto.GiftSendRequest;
import com.tailtopia.gift.dto.GiftSendResponse;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.service.GiftSendService;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.slot.PetSlotExpiryService;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/**
 * L1：V1.4.0 push-collect Story 3.1 · AC8 —— ④ 推送分类开关 × ①②③ 新通知（需 Docker postgres + redis）。
 *
 * <p>{@link MockitoBean} {@link NotificationPusher} 计推送次数；通知中心落库与未读角标一律照常。
 * Mock 集合与 {@code PushGateIntegrationTest} 相同（只 mock pusher），共用缓存上下文。
 * 「订单与账号」类不查库由 {@code PushPreferenceServiceTest}（L0，mock DAO 断言零交互）覆盖，这里只证端到端照推。
 */
class PushGateCrossLineIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private GiftSendService giftSend;
    @Autowired
    private GiftCatalogRepository catalog;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private GrowthLedgerService ledger;
    @Autowired
    private PetSlotExpiryService slotExpiry;
    @Autowired
    @Qualifier("applicationTaskExecutor")
    private ThreadPoolTaskExecutor asyncExecutor;

    @MockitoBean
    private NotificationPusher pusher;

    private GiftCatalogItem bone;
    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        long n = SEQ.incrementAndGet();
        bone = catalog.save(GiftCatalogItem.create("pcx" + n, "Bone" + n, "Tulang" + n, "https://cdn/g.png", 1_000,
                false, false, false, GiftKind.NORMAL, null, 0));
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
    }

    @AfterEach
    void restore() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    // ---------- helpers ----------

    private void setCategories(User u, Map<String, Boolean> categories) throws Exception {
        mvc.perform(patch("/api/v1/me/push-settings").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("categories", categories))))
                .andExpect(status().isOk());
    }

    private void awaitAsyncIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        ThreadPoolExecutor pool = asyncExecutor.getThreadPoolExecutor();
        while (pool.getActiveCount() > 0 || !pool.getQueue().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("业务异步池 10s 内未排空");
            }
            Thread.sleep(20);
        }
    }

    private long count(User u, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = ?",
                Long.class, u.getId(), type);
    }

    private long badge(User u) {
        String v = redis.opsForValue().get(NotificationService.UNREAD_KEY_PREFIX + u.getId());
        return v == null ? 0 : Long.parseLong(v);
    }

    private long pushes(User u, String type) {
        return org.mockito.Mockito.mockingDetails(pusher).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("pushToUser"))
                // 必须按值比：u.getId() 是 Long，两个 Long 用 == 是引用比较，仅 -128~127 命中装箱缓存才相等，
                // 全量跑 id 过 127 后恒不等 → 计数恒 0（CI 红、单跑绿）。
                .filter(i -> u.getId().equals(i.getArgument(0)) && type.equals(i.getArgument(3)))
                .count();
    }

    private PetProfile newPet(User owner) {
        PetProfile p = petProfiles.save(PetProfile.create(owner.getId(), PetType.CAT, "Mochi", null, null, null, null,
                "PCX-" + SEQ.incrementAndGet()));
        return p; // 同 GiftNotifyIntegrationTest：送礼夹具宠物不回收（有礼物墙 / 订单引用）
    }

    private User funded() {
        User u = newUser();
        wallet.credit(u.getId(), 100_000, PawCoinTxnType.TOPUP, "TEST", null, "pcx:" + u.getId());
        return u;
    }

    private void give(User sender, PetProfile pet) {
        giftSend.send(sender.getId(), pet.getId(), new GiftSendRequest(bone.getPublicToken(), 1, PayChannel.PAWCOIN,
                "pcx-" + SEQ.incrementAndGet(), "gift_page", null));
    }

    // ---------- AC8.1 ----------

    @Test
    @DisplayName("AC8.1：关「Hadiah」→ 收礼进通知中心、角标 +1、不推；未读期间同人再送仍不推；打开后再收礼推 1 次")
    void giftMuted() throws Exception {
        User b = newUser();
        PetProfile pet = newPet(b);
        User a = funded();
        setCategories(b, Map.of("GIFT", false));
        long before = badge(b);

        give(a, pet);
        assertThat(count(b, "PET_GIFT_RECEIVED")).isEqualTo(1);
        assertThat(badge(b) - before).isEqualTo(1);
        assertThat(pushes(b, "PET_GIFT_RECEIVED")).isZero();
        java.sql.Timestamp firstTouch = jdbc.queryForObject("SELECT updated_at FROM notifications "
                + "WHERE recipient_user_id = ? AND type = 'PET_GIFT_RECEIVED'", java.sql.Timestamp.class, b.getId());

        Thread.sleep(20);
        give(a, pet); // T-29 分支：同一送礼人未读期间再送 → touch 照常
        assertThat(count(b, "PET_GIFT_RECEIVED")).isEqualTo(1);
        java.sql.Timestamp secondTouch = jdbc.queryForObject("SELECT updated_at FROM notifications "
                + "WHERE recipient_user_id = ? AND type = 'PET_GIFT_RECEIVED'", java.sql.Timestamp.class, b.getId());
        assertThat(secondTouch).isAfter(firstTouch);
        assertThat(pushes(b, "PET_GIFT_RECEIVED")).isZero();

        setCategories(b, Map.of("GIFT", true));
        give(a, pet);
        assertThat(pushes(b, "PET_GIFT_RECEIVED")).isEqualTo(1);
    }

    // ---------- AC8.2 ----------

    @Test
    @DisplayName("AC8.2：送礼人关「Hadiah」不影响未送达退回推送（订单与账号）")
    void refundStillPushedWhenGiftMuted() throws Exception {
        User a = newUser();
        setCategories(a, Map.of("GIFT", false));
        PetProfile pet = newPet(newUser());
        long n = SEQ.incrementAndGet();
        GiftCatalogItem tenK = catalog.save(GiftCatalogItem.create("pcxq" + n, "Gift" + n, "Hadiah" + n,
                "https://cdn/g.png", 10_000, false, false, false, GiftKind.NORMAL, null, 0)); // QRIS 单笔最低额以上
        GiftSendResponse r = giftSend.send(a.getId(), pet.getId(), new GiftSendRequest(tenK.getPublicToken(), 2,
                PayChannel.QRIS, "pcx-q-" + SEQ.incrementAndGet(), "gift_page", null));
        jdbc.update("DELETE FROM pet_profiles WHERE id = ?", pet.getId());

        paymentIntents.applyCallback(new PaymentCallback(r.payment().token(), "stub-" + r.payment().token(),
                GatewayStatus.PAID, Map.of()));
        awaitAsyncIdle();

        assertThat(count(a, "GIFT_UNDELIVERED_REFUND")).isEqualTo(1);
        assertThat(pushes(a, "GIFT_UNDELIVERED_REFUND")).isEqualTo(1);
    }

    // ---------- AC8.3 ----------

    @Test
    @DisplayName("AC8.3：关「Tonggak & level」→ Lv4→Lv5 有 GROWTH_LEVEL_UP 通知、不推、庆祝弹窗照常；打开后再升级推 1 次")
    void levelUpMuted() throws Exception {
        User b = newUser();
        Instant at = Instant.now();
        ledger.accrue(new AccrualCommand(b.getId(), RuleCode.GIFT_SENT, 495, "pcx-lv-a-" + b.getId(), "GIFT_ORDER",
                "a" + b.getId(), null, at));
        awaitAsyncIdle();
        long baseline = count(b, "GROWTH_LEVEL_UP");
        long pushBaseline = pushes(b, "GROWTH_LEVEL_UP");
        setCategories(b, Map.of("ACHIEVEMENT", false));

        ledger.accrue(new AccrualCommand(b.getId(), RuleCode.GIFT_SENT, 5, "pcx-lv-b-" + b.getId(), "GIFT_ORDER",
                "b" + b.getId(), null, at)); // 500 → Lv5
        awaitAsyncIdle();

        assertThat(count(b, "GROWTH_LEVEL_UP")).isEqualTo(baseline + 1);
        assertThat(jdbc.queryForObject("SELECT deep_link_type FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'GROWTH_LEVEL_UP' ORDER BY id DESC LIMIT 1", String.class, b.getId()))
                .isEqualTo("GROWTH_LEVEL_UP");
        assertThat(pushes(b, "GROWTH_LEVEL_UP")).isEqualTo(pushBaseline);
        mvc.perform(get("/api/v1/me/growth/popups").header(HttpHeaders.AUTHORIZATION, userBearer(b.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.celebration.toLevel").value(5));

        setCategories(b, Map.of("ACHIEVEMENT", true));
        ledger.accrue(new AccrualCommand(b.getId(), RuleCode.GIFT_SENT, 800, "pcx-lv-c-" + b.getId(), "GIFT_ORDER",
                "c" + b.getId(), null, at)); // 1300 → Lv7
        awaitAsyncIdle();
        assertThat(count(b, "GROWTH_LEVEL_UP")).isEqualTo(baseline + 2);
        assertThat(pushes(b, "GROWTH_LEVEL_UP")).isEqualTo(pushBaseline + 1);
    }

    // ---------- AC8.4 ----------

    @Test
    @DisplayName("AC8.4（PRD AC3）：关掉其余 8 类 → ② 名额即将到期 / 已到期两条照推")
    void petSlotNoticesAlwaysPushed() throws Exception {
        User u = newUser();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        for (String name : List.of("Mochi", "Kopi", "Teh")) {
            extraPets.add(MultiPetDbFixture.insertPet(jdbc, u.getId(), name, "CAT", t0.plusSeconds(extraPets.size() * 60L)));
        }
        wallet.credit(u.getId(), 100_000L, PawCoinTxnType.TOPUP, "TEST", null, "pcx-slot:" + u.getId());
        mvc.perform(post("/api/v1/pet-slots/me/rentals").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header("Idempotency-Key", "pcx-" + SEQ.incrementAndGet())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2,\"channel\":\"PAWCOIN\"}"))
                .andExpect(status().isOk());
        String body = mvc.perform(get("/api/v1/pet-slots/me").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode rentals = json.readTree(body).get("rentals");
        String expired = rentals.get(0).get("token").asString();
        String expiring = rentals.get(1).get("token").asString();
        jdbc.update("UPDATE pet_slot_rentals SET starts_at = now() - interval '60 seconds' - interval '30 days', "
                + "expires_at = now() - interval '60 seconds' WHERE public_token = ?", expired);
        jdbc.update("UPDATE pet_slot_rentals SET starts_at = now() + interval '1 day' - interval '30 days', "
                + "expires_at = now() + interval '1 day' WHERE public_token = ?", expiring);
        setCategories(u, Map.of("LIKE", false, "COMMENT", false, "MENTION", false, "FOLLOWER", false,
                "GIFT", false, "PET_REMINDER", false, "ACHIEVEMENT", false, "PROMO", false));

        slotExpiry.scan(Instant.now());
        awaitAsyncIdle();

        assertThat(count(u, "PET_SLOT_EXPIRED")).isEqualTo(1);
        assertThat(count(u, "PET_SLOT_EXPIRING")).isEqualTo(1);
        assertThat(pushes(u, "PET_SLOT_EXPIRED")).isEqualTo(1);
        assertThat(pushes(u, "PET_SLOT_EXPIRING")).isEqualTo(1);
        verify(pusher, never()).pushToUser(eq(u.getId()), any(), any(), eq("CONTENT_LIKED"), any(), any());
        verify(pusher, times(2)).pushToUser(eq(u.getId()), any(), any(), any(), any(), any());
    }
}

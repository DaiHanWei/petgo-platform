package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPetRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.support.ContentPetsTestFixture;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.dto.GiftSendRequest;
import com.tailtopia.gift.dto.GiftSendResponse;
import com.tailtopia.gift.port.GiftPetDirectory;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.service.GiftSendService;
import com.tailtopia.growth.service.AdminAdjustCommand;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * V1.4.0 gift Story 4.1 · L1（需 postgres + redis）：送礼与 ①② 合入后的跨线对齐。
 *
 * <ul>
 *   <li>AC1 / AC3：多宠 Diary 详情 {@code giftPets} 两项、与 {@code pets} 同序；只读宠物照列、照收；② 真实删除入口
 *       删宠物后墙 / 榜随之消失、订单 {@code pet_id} 置空；付款期间删宠物 → 交付时退回；</li>
 *   <li>AC2：{@code ?petId=A/B} 主人统计、App 内访客统计、H5 名片三处礼物总价值各自按宠物、互不串；</li>
 *   <li>AC7.3：等级只经 {@code GrowthQueryService}，① 合入后目录按真实等级过滤专属礼物；</li>
 *   <li>AC8：PawCoin 送 Rp10,000 → 送礼人 +50、主人 +40（① seed {@code GIFT_SENT} 5‰ / {@code GIFT_RECEIVED} 4‰），
 *       各一条流水；退回单无流水。</li>
 * </ul>
 */
class GiftAlignmentIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private GiftSendService sendService;
    @Autowired
    private GiftCatalogRepository catalog;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ContentPetRepository contentPets;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private GrowthLedgerService growthLedger;
    @Autowired
    private GiftPetDirectory directory;
    @Autowired
    private JdbcTemplate jdbc;

    private GiftCatalogItem g53k;
    private GiftCatalogItem g5k;
    private GiftCatalogItem g10k;
    private GiftCatalogItem exclusive5;
    private GiftCatalogItem exclusive15;

    private GiftCatalogItem saveGift(long price, GiftKind kind, Integer minLevel) {
        long n = SEQ.incrementAndGet();
        return catalog.save(GiftCatalogItem.create("ga" + n, "Gift" + n, "Hadiah" + n, "https://cdn/g.png", price,
                false, false, false, kind, minLevel, -1_000_000));
    }

    @BeforeEach
    void seedCatalog() {
        g53k = saveGift(53_000, GiftKind.NORMAL, null);
        g5k = saveGift(5_000, GiftKind.NORMAL, null);
        g10k = saveGift(10_000, GiftKind.NORMAL, null);
        exclusive5 = saveGift(20_000, GiftKind.EXCLUSIVE, 5);
        exclusive15 = saveGift(30_000, GiftKind.EXCLUSIVE, 15);
    }

    // ———————————————— 夹具 ————————————————

    private PetProfile newPet(User owner, String name, PetType type) {
        return petProfiles.saveAndFlush(PetProfile.create(owner.getId(), type, name, null, null, null, null,
                "GA-" + SEQ.incrementAndGet()));
    }

    /** 主人的公开 Diary，关联 [pets]（按宠物创建顺序存关联；pet_id = 第一只）。 */
    private long diaryOf(User owner, PetProfile... pets) {
        ContentPost p = ContentPetsTestFixture.link(contentPets, posts.saveAndFlush(ContentPost.publish(owner.getId(),
                ContentType.GROWTH_MOMENT, pets[0].getId(), "diary", List.of(), LocalDate.of(2026, 10, 1))));
        for (int i = 1; i < pets.length; i++) {
            ContentPetsTestFixture.linkPet(jdbc, p.getId(), pets[i].getId());
        }
        return p.getId();
    }

    private User funded() {
        User u = newUser();
        wallet.credit(u.getId(), 500_000, PawCoinTxnType.TOPUP, "TEST", null, "ga-topup:" + SEQ.incrementAndGet());
        return u;
    }

    private GiftSendResponse send(User sender, PetProfile pet, GiftCatalogItem gift, PayChannel channel) {
        return sendService.send(sender.getId(), pet.getId(), new GiftSendRequest(gift.getPublicToken(), 1, channel,
                "ga-" + SEQ.incrementAndGet(), "content_detail", null));
    }

    private long orderId(GiftSendResponse r) {
        return jdbc.queryForObject("SELECT id FROM gift_orders WHERE public_token = ?", Long.class, r.orderToken());
    }

    private JsonNode getJson(String url, long viewer) throws Exception {
        return json.readTree(mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, userBearer(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        Thread.sleep(150);
    }

    private long ledgerRows(String idemKey) {
        return jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE idem_key = ?", Long.class, idemKey);
    }

    private long wallRows(long petId) {
        return jdbc.queryForObject("SELECT count(*) FROM gift_pet_wall WHERE pet_id = ?", Long.class, petId);
    }

    private long supporterRows(long petId) {
        return jdbc.queryForObject("SELECT count(*) FROM gift_pet_supporters WHERE pet_id = ?", Long.class, petId);
    }

    private void adjustExp(long userId, int delta) {
        growthLedger.adjust(new AdminAdjustCommand(userId, AdminAdjustCommand.AdjustType.ADMIN_ADJUST, delta,
                "gift 4.1 it", adminActorId(), "ga-adj-" + SEQ.incrementAndGet()));
    }

    // ———————————————— AC1 / AC3 ————————————————

    @Test
    void diaryWithTwoPetsListsBothGiftPetsInPetsOrderAndGiftsGoToTheChosenOne() throws Exception {
        User owner = newUser();
        PetProfile a = newPet(owner, "Aaa", PetType.CAT);
        PetProfile b = newPet(owner, "Bbb", PetType.DOG);
        long postId = diaryOf(owner, a, b);
        User sender = funded();

        assertThat(directory.petsOfPost(postId)).extracting(GiftPetDirectory.GiftPet::petId)
                .containsExactly(a.getId(), b.getId());

        send(sender, b, g5k, PayChannel.PAWCOIN);

        JsonNode d = getJson("/api/v1/content-posts/" + postId, sender.getId());
        List<Long> pets = new ArrayList<>();
        d.get("pets").forEach(p -> pets.add(p.get("petId").asLong()));
        List<Long> giftPets = new ArrayList<>();
        d.get("giftPets").forEach(p -> giftPets.add(p.get("petId").asLong()));
        assertThat(pets).containsExactly(a.getId(), b.getId());
        assertThat(giftPets).as("giftPets 与 pets 逐一同序（AC3.1）").isEqualTo(pets);
        assertThat(d.get("giftPets").get(0).get("giftCount").asLong()).as("A 不变").isZero();
        assertThat(d.get("giftPets").get(1).get("giftCount").asLong()).as("B 收到").isEqualTo(1L);
        assertThat(wallRows(a.getId())).isZero();
        assertThat(wallRows(b.getId())).isEqualTo(1L);
    }

    @Test
    void readOnlyPetIsListedAndStillReceives_privateOrMomentPostHasNoGiftPets() throws Exception {
        User owner = newUser();
        PetProfile a = newPet(owner, "Aaa", PetType.CAT);
        PetProfile b = newPet(owner, "Bbb", PetType.CAT);
        long postId = diaryOf(owner, a, b);
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", b.getId());
        User sender = funded();

        assertThat(directory.petsOfPost(postId)).extracting(GiftPetDirectory.GiftPet::petId)
                .containsExactly(a.getId(), b.getId());
        GiftSendResponse r = send(sender, b, g5k, PayChannel.PAWCOIN);
        assertThat(r.delivered()).as("只读宠物可收礼（AC1.3）").isTrue();

        long moment = posts.saveAndFlush(ContentPost.publish(owner.getId(), ContentType.DAILY, null, "m", List.of()))
                .getId();
        assertThat(getJson("/api/v1/content-posts/" + moment, sender.getId()).has("giftPets"))
                .as("Moment 无 giftPets（NON_NULL 省略）").isFalse();
        jdbc.update("UPDATE content_posts SET visibility = 'PRIVATE' WHERE id = ?", postId);
        assertThat(directory.petsOfPost(postId)).as("私密 Diary 不可送礼").isEmpty();
    }

    @Test
    void deletingAPetThroughMultiPetEntryClearsItsWallAndNullsOrderPetId() throws Exception {
        User owner = newUser();
        PetProfile a = newPet(owner, "Aaa", PetType.CAT);
        PetProfile b = newPet(owner, "Bbb", PetType.DOG);
        long postId = diaryOf(owner, a, b);
        User sender = funded();
        send(sender, a, g5k, PayChannel.PAWCOIN);
        long bOrder = orderId(send(sender, b, g10k, PayChannel.PAWCOIN));
        assertThat(supporterRows(b.getId())).isEqualTo(1L);

        profileDeletion.deleteByPetId(owner.getId(), b.getId());

        assertThat(wallRows(b.getId())).isZero();
        assertThat(supporterRows(b.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT pet_id FROM gift_orders WHERE id = ?", Long.class, bOrder)).isNull();
        assertThat(wallRows(a.getId())).as("A 不受影响").isEqualTo(1L);
        assertThat(directory.petsOfPost(postId)).extracting(GiftPetDirectory.GiftPet::petId)
                .as("已删宠物不出现").containsExactly(a.getId());
        assertThat(directory.pet(b.getId())).isEmpty();
    }

    @Test
    void petDeletedWhileQrisPendingIsRefundedAndEarnsNoGrowth() throws Exception {
        User owner = newUser();
        PetProfile a = newPet(owner, "Aaa", PetType.CAT);
        PetProfile b = newPet(owner, "Bbb", PetType.DOG);
        User sender = newUser();
        GiftSendResponse pending = send(sender, b, g53k, PayChannel.QRIS);
        long order = orderId(pending);

        profileDeletion.deleteByPetId(owner.getId(), b.getId());
        paymentIntents.applyCallback(new PaymentCallback(pending.payment().token(), "stub-" + pending.payment().token(),
                GatewayStatus.PAID, Map.of()));

        await(() -> jdbc.queryForObject("SELECT status FROM gift_orders WHERE id = ?", String.class, order)
                .startsWith("UNDELIVERED"));
        assertThat(jdbc.queryForObject("SELECT status FROM gift_orders WHERE id = ?", String.class, order))
                .startsWith("UNDELIVERED");
        assertThat(ledgerRows("GIFT_SENT:" + order)).as("退回 / 未送达不入账（AD-9）").isZero();
        assertThat(ledgerRows("GIFT_RECEIVED:" + order)).isZero();
        assertThat(wallRows(a.getId())).isZero();
    }

    // ———————————————— AC2 ————————————————

    @Test
    void giftTotalValueIsPerPetInOwnerVisitorAndCardStats() throws Exception {
        User owner = newUser();
        PetProfile a = newPet(owner, "Aaa", PetType.CAT);
        PetProfile b = newPet(owner, "Bbb", PetType.DOG);
        User sender = funded();
        send(sender, a, g53k, PayChannel.PAWCOIN);
        send(sender, b, g5k, PayChannel.PAWCOIN);

        assertThat(getJson("/api/v1/pet-profiles/me/archive-stats?petId=" + a.getId(), owner.getId())
                .get("giftTotalValue").asLong()).isEqualTo(53_000L);
        assertThat(getJson("/api/v1/pet-profiles/me/archive-stats?petId=" + b.getId(), owner.getId())
                .get("giftTotalValue").asLong()).isEqualTo(5_000L);
        assertThat(getJson("/api/v1/pet-profiles/me/archive-stats", owner.getId()).get("giftTotalValue").asLong())
                .as("不传 petId = 默认宠物（最早建的 A，② P-4）").isEqualTo(53_000L);

        User visitor = newUser();
        assertThat(getJson("/api/v1/pets/" + a.getId() + "/visitor/stats", visitor.getId())
                .get("giftTotalValue").asLong()).isEqualTo(53_000L);
        JsonNode vb = getJson("/api/v1/pets/" + b.getId() + "/visitor/stats", visitor.getId());
        assertThat(vb.get("giftTotalValue").asLong()).isEqualTo(5_000L);
        assertThat(vb.has("consultCount")).as("consultCount 保留给旧版 App").isTrue();

        String cardA = mvc.perform(get("/p/" + a.getCardToken())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String cardB = mvc.perform(get("/p/" + b.getCardToken())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(cardA).contains("Rp</span><span>53</span>");
        assertThat(cardB).contains("Rp</span><span>5</span>").doesNotContain("Rp</span><span>53</span>");
    }

    // ———————————————— AC7.3 ————————————————

    @Test
    void catalogFollowsRealGrowthLevel() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner, "Aaa", PetType.CAT);
        User sender = newUser();
        adjustExp(sender.getId(), 250); // Lv4 门槛 250
        String url = "/api/v1/gifts/catalog?petId=" + pet.getId();

        JsonNode lv4 = getJson(url, sender.getId());
        assertThat(lv4.toString()).doesNotContain(exclusive5.getPublicToken());
        assertThat(lv4.get("lockBlock").get("requiredLevel").asInt()).isEqualTo(5);

        adjustExp(sender.getId(), 250); // 累计 500 = Lv5
        JsonNode lv5 = getJson(url, sender.getId());
        assertThat(lv5.toString()).contains(exclusive5.getPublicToken()).doesNotContain(exclusive15.getPublicToken());
        assertThat(lv5.get("lockBlock").get("requiredLevel").asInt()).isEqualTo(15);

        adjustExp(sender.getId(), 12_000); // 累计 12,500 ≥ Lv15 门槛 12,400
        JsonNode lv15 = getJson(url, sender.getId());
        assertThat(lv15.toString()).contains(exclusive5.getPublicToken(), exclusive15.getPublicToken());
        JsonNode lock = lv15.get("lockBlock");
        // 共享测试库可能另有更高门槛的专属礼物：本测试只断言「不再锁 Lv15 及以下」。
        assertThat(lock == null || lock.isNull() || lock.get("requiredLevel").asInt() > 15).isTrue();
    }

    // ———————————————— AC8 ————————————————

    @Test
    void pawcoinTenThousandGivesSenderFiftyAndOwnerFortyExp() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner, "Aaa", PetType.CAT);
        User sender = funded();
        long order = orderId(send(sender, pet, g10k, PayChannel.PAWCOIN));

        await(() -> ledgerRows("GIFT_RECEIVED:" + order) == 1 && ledgerRows("GIFT_SENT:" + order) == 1);
        assertThat(jdbc.queryForList("SELECT user_id || ':' || delta FROM growth_ledger WHERE idem_key = ?",
                String.class, "GIFT_SENT:" + order)).containsExactly(sender.getId() + ":50");
        assertThat(jdbc.queryForList("SELECT user_id || ':' || delta FROM growth_ledger WHERE idem_key = ?",
                String.class, "GIFT_RECEIVED:" + order)).containsExactly(owner.getId() + ":40");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ? AND rule_code LIKE 'SPEND%'",
                Long.class, sender.getId())).as("GIFT 不走消费返").isZero();
    }
}

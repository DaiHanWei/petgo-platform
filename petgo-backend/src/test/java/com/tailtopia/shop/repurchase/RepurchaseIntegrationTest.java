package com.tailtopia.shop.repurchase;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.domain.AgeStage;
import com.tailtopia.shop.domain.BodySize;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.Species;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repurchase.domain.ProfileFacts;
import com.tailtopia.shop.repurchase.domain.RepurchaseTriggerStatus;
import com.tailtopia.shop.repurchase.domain.RepurchaseTriggerType;
import com.tailtopia.shop.repurchase.repository.RepurchaseTriggerRepository;
import com.tailtopia.shop.repurchase.service.ProfileRecommendationService;
import com.tailtopia.shop.repurchase.service.RepurchaseScanService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：档案推荐（Story 6.2，FR-107）+ 粮量见底日扫（Story 6.3，FR-109）。
 *
 * <p>🔴 <b>「缺输入不触发」按一等路径测</b>：按当前 DEP-6 状态（每日建议喂量数据未到位），
 * 上线首日 FR-109 极可能对全体用户不触发 —— 那时日扫必须<b>安静地跑完并产生 0 条记录</b>，
 * 而不是报错或写一堆脏数据。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class RepurchaseIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ProfileRecommendationService recommendations;
    @Autowired
    private RepurchaseScanService repurchase;
    @Autowired
    private RepurchaseTriggerRepository triggers;
    @Autowired
    private PetProfileRepository profiles;
    @Autowired
    private ShopProductRepository products;
    @Autowired
    private CheckoutService checkout;
    @Autowired
    private ShopOrderPaymentService payments;
    @Autowired
    private ShopOrderFulfillmentService fulfillment;
    @Autowired
    private CartService carts;
    @Autowired
    private ShippingAddressService addresses;
    @Autowired
    private AdminShippingZoneService zones;
    @Autowired
    private AdminShopPawcoinRulesService rules;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private ShopOrderRepository orders;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    // ---------- 造数 ----------

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "rp" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "rp" + n);
    }

    /** 建一只宠物档案。weightKg=null 模拟「用户跳过了体重」。 */
    private PetProfile seedPet(long ownerId, String petType, LocalDate birthday,
            BigDecimal weightKg) {
        jdbc.update("""
                INSERT INTO pet_profiles (owner_id, pet_type, name, birthday, card_token,
                        weight_kg, created_at, updated_at)
                VALUES (?, ?, 'Mochi', ?, ?, ?, now(), now())
                """, ownerId, petType, birthday, "pc" + SEQ.incrementAndGet(), weightKg);
        return profiles.findByOwnerId(ownerId).orElseThrow();
    }

    /**
     * 建一个粮商品 + 一个 SKU。
     *
     * @param feedingGuideJson null 模拟 DEP-6 未到位（商品没配日喂量）
     */
    private String seedFoodSku(Species species, AgeStage age, BodySize size, long netWeightG,
            String feedingGuideJson, int sortWeight) {
        String pToken = "rpp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, body_size, age_stage, detail_html, shelf_life_note,
                        return_policy, is_active, sort_weight, feeding_guide)
                VALUES (?, ?, 'B', 'MAKANAN', 'k', ?, ?, ?, '<p/>', 'n', 'RETURNABLE', true, ?,
                        CAST(? AS jsonb))
                """, pToken, "Food-" + pToken, species.name(), size.name(), age.name(), sortWeight,
                feedingGuideJson);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "rps" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, net_weight_g)
                VALUES (?, ?, '3 kg', 100000, ?)""", sToken, pid, netWeightG);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 100, 0)", sid);
        return sToken;
    }

    /** 下一单该 SKU 并推到【已签收】，送达日回拨 daysAgo 天。 */
    private ShopOrder deliveredOrderFor(long uid, String skuToken, int qty, int deliveredDaysAgo) {
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 5_000_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "rp-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Krp" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        carts.add(uid, skuToken, qty);
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, o.getPublicToken(), null);
        fulfillment.ship(o.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 0L);
        fulfillment.markDeliveredByAdmin(o.getPublicToken());
        jdbc.update("UPDATE shop_orders SET delivered_at = ? WHERE public_token = ?",
                java.sql.Timestamp.from(
                        java.time.Instant.now().minus(deliveredDaysAgo, ChronoUnit.DAYS)),
                o.getPublicToken());
        return orders.findByPublicToken(o.getPublicToken()).orElseThrow();
    }

    /**
     * 🔴 <b>断言一律按 user 维度，不看 {@code scan()} 的全局返回值。</b>
     *
     * <p>日扫扫的是全库已签收订单 —— 同一个测试类里别的用例造的数据也会被算进去。
     * 用全局计数断言的用例<b>单跑绿、全类跑红</b>，是本仓库最误导人的一类失败
     * （HANDOFF §测试基建）。
     */
    private long triggerCountFor(long userId) {
        return triggers.findAll().stream().filter(t -> t.getUserId() == userId).count();
    }

    // ---------- Story 6.2 推荐规则引擎 ----------

    @Test
    @DisplayName("四步过滤：物种硬过滤 + 年龄段 + 体型 + 权重排序取前 N")
    void fourStepFilter() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        // 命中：狗 · 成年 · 中型
        String hit = seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.MEDIUM, 3000L, null, 100);
        // 不命中：猫
        seedFoodSku(Species.CAT, AgeStage.ADULT, BodySize.MEDIUM, 3000L, null, 200);
        // 不命中：幼犬
        seedFoodSku(Species.DOG, AgeStage.PUPPY, BodySize.MEDIUM, 3000L, null, 200);
        // 不命中：大型犬
        seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.LARGE, 3000L, null, 200);

        var view = recommendations.recommendFor(uid);

        assertThat(view.degraded()).isFalse();
        assertThat(view.items()).isNotEmpty();
        var tokens = view.items().stream().map(i -> i.productToken()).toList();
        String hitProductToken = jdbc.queryForObject("""
                SELECT p.public_token FROM shop_products p JOIN shop_skus s ON s.product_id = p.id
                WHERE s.public_token = ?""", String.class, hit);
        assertThat(tokens).contains(hitProductToken);
    }

    @Test
    @DisplayName("🔴 每条结果都带推荐理由 —— 不可解释的推荐在信任驱动的产品里是负资产")
    void everyItemHasAReason() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.MEDIUM, 3000L, null, 100);

        var view = recommendations.recommendFor(uid);
        assertThat(view.items()).isNotEmpty();
        assertThat(view.items()).allSatisfy(i -> {
            assertThat(i.reason()).isNotBlank();
            // 理由必须来自实际用上的维度，不是一句放之四海皆准的话
            assertThat(i.reason()).contains("anjing");
        });
    }

    @Test
    @DisplayName("🔴 无体重 → 降级为按物种推荐，degraded=true / missing=WEIGHT，不报错不返回空")
    void degradesWhenWeightMissing() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), null);
        seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.LARGE, 3000L, null, 100);

        var view = recommendations.recommendFor(uid);

        assertThat(view.degraded()).isTrue();
        assertThat(view.missing()).isEqualTo("WEIGHT");
        // 🔴 降级路径不返回空：大型犬粮在无体重时也该被推出来
        assertThat(view.items()).isNotEmpty();
    }

    @Test
    @DisplayName("🔴 无年龄 / 两者皆无 各自降级")
    void degradesWhenAgeOrBothMissing() {
        long uidNoAge = seedUser();
        seedPet(uidNoAge, "DOG", null, new BigDecimal("15"));
        seedFoodSku(Species.DOG, AgeStage.PUPPY, BodySize.MEDIUM, 3000L, null, 100);
        var noAge = recommendations.recommendFor(uidNoAge);
        assertThat(noAge.degraded()).isTrue();
        assertThat(noAge.missing()).isEqualTo("AGE");

        long uidNeither = seedUser();
        seedPet(uidNeither, "DOG", null, null);
        var neither = recommendations.recommendFor(uidNeither);
        assertThat(neither.degraded()).isTrue();
        assertThat(neither.missing()).isEqualTo("BOTH");
    }

    @Test
    @DisplayName("未建档 → degraded=true / missing=PROFILE / 空列表（前端换成建档引导卡）")
    void noProfileYieldsProfileMissing() {
        var view = recommendations.recommendFor(seedUser());
        assertThat(view.degraded()).isTrue();
        assertThat(view.missing()).isEqualTo("PROFILE");
        assertThat(view.items()).isEmpty();
    }

    @Test
    @DisplayName("🔴 结果上限 6 条（FR-107：4–6 个）")
    void capsAtSixItems() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        for (int i = 0; i < 9; i++) {
            seedFoodSku(Species.DOG, AgeStage.UNIVERSAL, BodySize.UNIVERSAL, 3000L, null, 50 + i);
        }
        assertThat(recommendations.recommendFor(uid).items())
                .hasSizeLessThanOrEqualTo(ProfileRecommendationService.MAX_ITEMS);
    }

    @Test
    @DisplayName("年龄分档：<1 岁 PUPPY / 1–7 岁 ADULT / >7 岁 SENIOR")
    void ageStageThresholds() {
        LocalDate today = LocalDate.of(2026, 8, 18);
        assertThat(ProfileFacts.ageStageOf(today.minusMonths(6), today, 1, 7))
                .isEqualTo(AgeStage.PUPPY);
        assertThat(ProfileFacts.ageStageOf(today.minusYears(3), today, 1, 7))
                .isEqualTo(AgeStage.ADULT);
        assertThat(ProfileFacts.ageStageOf(today.minusYears(9), today, 1, 7))
                .isEqualTo(AgeStage.SENIOR);
    }

    // ---------- Story 6.3 粮量见底日扫 ----------

    @Test
    @DisplayName("🔴 正常路径：耗尽日前 7 天内 → 生成一条 FOOD_LOW 触发并推送一次")
    void scanCreatesTriggerAndNotifiesOnce() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        // 15kg → 110 g/天；3000 g / 110 ≈ 27 天。送达 22 天前 → 还剩 5 天 → 在 7 天窗口内
        String sku = seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.MEDIUM, 3000L,
                "[{\"weightMinKg\":10,\"weightMaxKg\":25,\"gramsPerDay\":110}]", 100);
        deliveredOrderFor(uid, sku, 1, 22);

        repurchase.scan(LocalDate.now(ZoneOffset.UTC));

        assertThat(triggerCountFor(uid)).isEqualTo(1);
        var active = repurchase.activeTriggersFor(uid);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getTriggerType()).isEqualTo(RepurchaseTriggerType.FOOD_LOW);
        assertThat(active.get(0).isNotified()).isTrue();
        Long pushes = jdbc.queryForObject("""
                SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = ?""",
                Long.class, uid, NotificationType.REPURCHASE_FOOD_LOW.name());
        assertThat(pushes).isEqualTo(1L);

        // 🔴 再跑一次日扫：不重复建、不重推
        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(triggerCountFor(uid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = ?""",
                Long.class, uid, NotificationType.REPURCHASE_FOOD_LOW.name())).isEqualTo(1L);
    }

    @Test
    @DisplayName("🔴 还没到 7 天窗口 → 不触发")
    void tooEarlyDoesNotTrigger() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        String sku = seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.MEDIUM, 3000L,
                "[{\"weightMinKg\":10,\"weightMaxKg\":25,\"gramsPerDay\":110}]", 100);
        deliveredOrderFor(uid, sku, 1, 1);   // 刚送到，还能吃 26 天

        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(triggerCountFor(uid)).isZero();
        assertThat(repurchase.activeTriggersFor(uid)).isEmpty();
    }

    @Test
    @DisplayName("🔴 修正①在真链路上生效：qty=3 时同样的送达日不再触发（还能吃 81 天）")
    void quantityCorrectionAppliesEndToEnd() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        String sku = seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.MEDIUM, 3000L,
                "[{\"weightMinKg\":10,\"weightMaxKg\":25,\"gramsPerDay\":110}]", 100);
        deliveredOrderFor(uid, sku, 3, 22);

        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(triggerCountFor(uid))
                .as("🔴 买 3 袋按 1 袋算的话，这里会误触发")
                .isZero();
    }

    // ---------- 🔴 DEP-6 未到位：一等路径 ----------

    @Test
    @DisplayName("🔴🔴 全库无喂量数据（DEP-6 未到位）→ 日扫不报错、不产生任何记录")
    void depSixMissingScansSilently() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        String sku = seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.MEDIUM, 3000L, null, 100);
        deliveredOrderFor(uid, sku, 1, 22);

        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(triggerCountFor(uid))
                .as("🔴 这是上线首日的常态，不是异常").isZero();
        assertThat(repurchase.activeTriggersFor(uid)).isEmpty();
    }

    @Test
    @DisplayName("🔴 档案无体重 → 日扫静默（缺输入不猜、不报错）")
    void noWeightScansSilently() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), null);
        String sku = seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.MEDIUM, 3000L,
                "[{\"weightMinKg\":10,\"weightMaxKg\":25,\"gramsPerDay\":110}]", 100);
        deliveredOrderFor(uid, sku, 1, 22);

        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(triggerCountFor(uid)).isZero();
    }

    @Test
    @DisplayName("🔴 无购买历史 → 日扫静默")
    void noPurchaseHistoryScansSilently() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(triggerCountFor(uid)).isZero();
        assertThat(repurchase.activeTriggersFor(uid)).isEmpty();
    }

    // ---------- 再次购买 → 旧触发立即失效 ----------

    @Test
    @DisplayName("🔴 用户再次购买该 SKU → 旧触发立即失效（读时就地判定），按新订单重新起算")
    void repurchaseSupersedesOldTrigger() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        String sku = seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.MEDIUM, 3000L,
                "[{\"weightMinKg\":10,\"weightMaxKg\":25,\"gramsPerDay\":110}]", 100);
        deliveredOrderFor(uid, sku, 1, 22);
        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(repurchase.activeTriggersFor(uid)).hasSize(1);

        // 再买一次同一 SKU
        deliveredOrderFor(uid, sku, 1, 0);

        assertThat(repurchase.activeTriggersFor(uid))
                .as("🔴 明明刚买过还一直看到「快没粮了」，是这条机制最伤信任的失败方式")
                .isEmpty();
        assertThat(triggers.findAll().stream()
                .filter(t -> t.getUserId() == uid)
                .allMatch(t -> t.getStatus() == RepurchaseTriggerStatus.SUPERSEDED)).isTrue();
    }

    // ---------- 本版本只产生 FOOD_LOW ----------

    @Test
    @DisplayName("⚠️ 本版本只会产生 FOOD_LOW —— DEWORM/VACCINE 恒为 0 是范围决策（C-11），不是数据丢失")
    void onlyFoodLowIsProducedInThisVersion() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        String sku = seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.MEDIUM, 3000L,
                "[{\"weightMinKg\":10,\"weightMaxKg\":25,\"gramsPerDay\":110}]", 100);
        deliveredOrderFor(uid, sku, 1, 22);
        repurchase.scan(LocalDate.now(ZoneOffset.UTC));

        assertThat(triggers.findAll()).isNotEmpty().allSatisfy(
                t -> assertThat(t.getTriggerType()).isEqualTo(RepurchaseTriggerType.FOOD_LOW));
        // 枚举仍保留三值 —— 供 1.2.0 FR-108 上线时直接追加数据、不断历史序列
        assertThat(RepurchaseTriggerType.values()).hasSize(3);
    }

    @Test
    @DisplayName("非 Makanan 类目（如零食）不参与粮量预估 —— 零食不是主粮，吃完不该催补货")
    void nonFoodCategoryIgnored() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        String pToken = "rpn" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active,
                        feeding_guide)
                VALUES (?, 'Camilan', 'B', 'CAMILAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true,
                        CAST(? AS jsonb))
                """, pToken, "[{\"weightMinKg\":10,\"weightMaxKg\":25,\"gramsPerDay\":110}]");
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "rpns" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, net_weight_g)
                VALUES (?, ?, 'M', 50000, 3000)""", sToken, pid);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 100, 0)", sid);
        deliveredOrderFor(uid, sToken, 1, 22);

        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(triggerCountFor(uid)).isZero();
    }

    @Test
    @DisplayName("已下架商品仍可被日扫算到 —— 用户手上的粮不会因为下架就不吃了")
    void delistedProductStillCounts() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        String sku = seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.MEDIUM, 3000L,
                "[{\"weightMinKg\":10,\"weightMaxKg\":25,\"gramsPerDay\":110}]", 100);
        deliveredOrderFor(uid, sku, 1, 22);
        jdbc.update("""
                UPDATE shop_products SET is_active = false WHERE id =
                    (SELECT product_id FROM shop_skus WHERE public_token = ?)""", sku);

        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(triggerCountFor(uid)).isEqualTo(1);
    }

    @Test
    @DisplayName("推荐只取在售商品（下架的不推）")
    void recommendationsOnlyIncludeActiveProducts() {
        long uid = seedUser();
        seedPet(uid, "DOG", LocalDate.now().minusYears(3), new BigDecimal("15"));
        String sku = seedFoodSku(Species.DOG, AgeStage.ADULT, BodySize.MEDIUM, 3000L, null, 100);
        String productToken = jdbc.queryForObject("""
                SELECT p.public_token FROM shop_products p JOIN shop_skus s ON s.product_id = p.id
                WHERE s.public_token = ?""", String.class, sku);
        jdbc.update("UPDATE shop_products SET is_active = false WHERE public_token = ?",
                productToken);

        var tokens = recommendations.recommendFor(uid).items().stream()
                .map(i -> i.productToken()).toList();
        assertThat(tokens).doesNotContain(productToken);
        List<ShopProduct> all = products.findByActiveTrueOrderBySortWeightDescIdDesc();
        assertThat(all).noneMatch(p -> p.getPublicToken().equals(productToken));
    }
}

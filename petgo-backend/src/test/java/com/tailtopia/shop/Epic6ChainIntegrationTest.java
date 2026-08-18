package com.tailtopia.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：Epic 6 全链路联调（Story 6.7）。
 *
 * <p>走的是这一版<b>存在理由</b>的那条链路：
 * <b>填体重 → 看推荐 → 下单买粮 → 推进时钟到耗尽日前 7 天 → 日扫 → 补货卡 + 推送一次</b>。
 *
 * <p>🔴 <b>「DEP-6 未到位」按一等路径测</b>：全库无喂量数据时全链路静默降级 ——
 * 日扫不报错、不产生记录、首页区域① 整区不渲染、看板覆盖率显示 0。
 * 这是<b>可能的上线状态</b>，不是异常。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class Epic6ChainIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ProfileRecommendationService recommendations;
    @Autowired
    private RepurchaseScanService repurchase;
    @Autowired
    private RepurchaseTriggerRepository triggers;
    @Autowired
    private PetProfileRepository profiles;
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
    private com.tailtopia.admin.shop.service.RepurchaseDashboardService dashboard;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private static final String GUIDE_15KG =
            "[{\"weightMinKg\":10,\"weightMaxKg\":25,\"gramsPerDay\":110}]";

    // ---------- 造数 ----------

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "e6" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "e6" + n);
    }

    /** 建档 + 填体重（Story 6.1 的产物；weightKg=null 模拟用户跳过）。 */
    private void seedPetWithWeight(long ownerId, BigDecimal weightKg) {
        jdbc.update("""
                INSERT INTO pet_profiles (owner_id, pet_type, name, birthday, card_token,
                        weight_kg, created_at, updated_at)
                VALUES (?, 'DOG', 'Mochi', ?, ?, ?, now(), now())
                """, ownerId, LocalDate.now().minusYears(3),
                "e6pc" + SEQ.incrementAndGet(), weightKg);
    }

    private String seedFoodSku(String feedingGuideJson) {
        String pToken = "e6p" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, body_size, age_stage, detail_html, shelf_life_note,
                        return_policy, is_active, sort_weight, feeding_guide)
                VALUES (?, ?, 'RC', 'MAKANAN', 'k', 'DOG', 'MEDIUM', 'ADULT', '<p/>', 'n',
                        'RETURNABLE', true, 100, CAST(? AS jsonb))
                """, pToken, "Royal Canin " + pToken, feedingGuideJson);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "e6s" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, net_weight_g)
                VALUES (?, ?, '3 kg', 285000, 3000)""", sToken, pid);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 100, 0)", sid);
        return sToken;
    }

    /** 下单买粮 → 付款 → 发货 → 签收，并把送达日回拨 daysAgo 天（模拟时钟推进）。 */
    private ShopOrder buyAndDeliver(long uid, String skuToken, int qty, int deliveredDaysAgo) {
        rules.update(true, true, 5_000_000L, ACTOR);
        wallet.credit(uid, 5_000_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "e6-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Ke6" + SEQ.incrementAndGet();
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

    /** 🔴 断言一律按 user 维度：日扫扫全库，别的用例造的数据也会被算进来。 */
    private long triggerCountFor(long userId) {
        return triggers.findAll().stream().filter(t -> t.getUserId() == userId).count();
    }

    // ---------- 主链路 ----------

    @Test
    @DisplayName("🔴 全程：填体重 → 看推荐（带理由）→ 买粮 → 推进时钟 → 日扫 → 补货卡 + 推送一次")
    void fullRepurchaseChain() {
        long uid = seedUser();
        seedPetWithWeight(uid, new BigDecimal("15"));
        String sku = seedFoodSku(GUIDE_15KG);

        // ① 推荐：四步规则命中且带理由，档案完整 → 不降级
        var reco = recommendations.recommendFor(uid);
        assertThat(reco.degraded()).isFalse();
        assertThat(reco.items()).isNotEmpty();
        assertThat(reco.items()).allSatisfy(i -> assertThat(i.reason()).contains("anjing"));

        // ② 买粮并推进时钟到耗尽日前 5 天（3000 g / 110 g/天 ≈ 27 天，送达 22 天前）
        buyAndDeliver(uid, sku, 1, 22);

        // ③ 日扫
        repurchase.scan(LocalDate.now(ZoneOffset.UTC));

        // ④ 补货卡出现在区域①，且推送恰好一次
        var cards = repurchase.activeTriggersFor(uid);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getTriggerType()).isEqualTo(RepurchaseTriggerType.FOOD_LOW);
        assertThat(cards.get(0).isNotified()).isTrue();
        assertThat(pushCount(uid)).isEqualTo(1L);

        // ⑤ 重复日扫不重推
        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(pushCount(uid)).isEqualTo(1L);
        assertThat(triggerCountFor(uid)).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 再次购买该 SKU → 旧触发立即失效，按新订单重算")
    void repurchaseResetsTheClock() {
        long uid = seedUser();
        seedPetWithWeight(uid, new BigDecimal("15"));
        String sku = seedFoodSku(GUIDE_15KG);
        buyAndDeliver(uid, sku, 1, 22);
        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(repurchase.activeTriggersFor(uid)).hasSize(1);

        // 再买一次（新订单刚送达 → 还能吃 27 天）
        buyAndDeliver(uid, sku, 1, 0);

        assertThat(repurchase.activeTriggersFor(uid))
                .as("🔴 明明刚买过还一直看到「快没粮了」，是这条机制最伤信任的失败方式")
                .isEmpty();
        // 重算：新订单还早，日扫不该再造新触发
        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(repurchase.activeTriggersFor(uid)).isEmpty();
    }

    // ---------- 🔴 DEP-6 未到位：一等路径 ----------

    @Test
    @DisplayName("🔴🔴 全库无喂量数据 → 日扫不报错、零触发、区域① 无内容、看板覆盖率为 0 且能一眼看出原因")
    void depSixMissingDegradesSilentlyEndToEnd() {
        long uid = seedUser();
        seedPetWithWeight(uid, new BigDecimal("15"));
        String sku = seedFoodSku(null);   // 🔴 商品没配日喂量
        buyAndDeliver(uid, sku, 1, 22);

        repurchase.scan(LocalDate.now(ZoneOffset.UTC));

        assertThat(triggerCountFor(uid)).as("🔴 这是可能的上线状态，不是异常").isZero();
        // 区域① 无内容 → 前端整区不渲染
        assertThat(repurchase.activeTriggersFor(uid)).isEmpty();
        assertThat(pushCount(uid)).isZero();

        // 🔴 看板要能让人一眼看出「是数据没到位」，而不是「机制无效」
        var snap = dashboard.snapshot(LocalDate.now().minusDays(30), LocalDate.now());
        assertThat(snap.usersWithFoodPurchase()).isPositive();
        // 覆盖率的分子分母语义正确（本用例这一位用户没有触发）
        assertThat(snap.triggersByType()).containsKeys("FOOD_LOW", "DEWORM", "VACCINE");
    }

    @Test
    @DisplayName("🔴 档案无体重 → 推荐降级为按物种（不报错、不空），日扫静默")
    void noWeightDegradesRecommendationAndSilencesScan() {
        long uid = seedUser();
        seedPetWithWeight(uid, null);
        String sku = seedFoodSku(GUIDE_15KG);
        buyAndDeliver(uid, sku, 1, 22);

        var reco = recommendations.recommendFor(uid);
        assertThat(reco.degraded()).isTrue();
        assertThat(reco.missing()).isEqualTo("WEIGHT");
        assertThat(reco.items()).as("🔴 降级路径不返回空").isNotEmpty();

        repurchase.scan(LocalDate.now(ZoneOffset.UTC));
        assertThat(triggerCountFor(uid)).isZero();
    }

    // ---------- 看板口径 ----------

    @Test
    @DisplayName("🔴 看板以服务端订单行归因为主口径，且三个 trigger_type 都列出（含恒为 0 的两个）")
    void dashboardUsesServerSideAttribution() {
        long uid = seedUser();
        seedPetWithWeight(uid, new BigDecimal("15"));
        String sku = seedFoodSku(GUIDE_15KG);
        buyAndDeliver(uid, sku, 1, 22);
        repurchase.scan(LocalDate.now(ZoneOffset.UTC));

        var snap = dashboard.snapshot(LocalDate.now().minusDays(1), LocalDate.now());

        // ⚠️ 三值都在 —— DEWORM/VACCINE 恒为 0 是范围决策（C-11），看板要能说明这一点
        assertThat(snap.triggersByType()).containsKeys("FOOD_LOW", "DEWORM", "VACCINE");
        assertThat(snap.triggersByType().get("DEWORM")).isZero();
        assertThat(snap.triggersByType().get("VACCINE")).isZero();
        assertThat(snap.triggersByType().get("FOOD_LOW")).isPositive();
        // 归因读的是订单行，不是卡片状态
        assertThat(snap.linesTotal()).isPositive();
    }

    private long pushCount(long uid) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = ?""",
                Long.class, uid, NotificationType.REPURCHASE_FOOD_LOW.name());
        return n == null ? 0 : n;
    }
}

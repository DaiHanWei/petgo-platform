package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** L1：PawCoin 电商消费规则配置（Story 3.5，AB-6D / AB-6A 扩展 / AB-6C）。 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class ShopPawcoinRulesIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminShopPawcoinRulesService admin;
    @Autowired
    private PawCoinConfigRepository pawcoinConfig;
    @Autowired
    private CheckoutService checkout;
    @Autowired
    private CartService carts;
    @Autowired
    private ShippingAddressService addresses;
    @Autowired
    private AdminShippingZoneService zones;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "pc" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "pc" + n);
    }

    private String seedSku(long stock, long price) {
        String pToken = "rp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'P', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n',
                        'NO_RETURN_AFTER_OPEN', true)""", pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "rs" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)""", sToken, pid, price);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)",
                sid, stock);
        return sToken;
    }

    private String seedAddress(long uid) {
        String kec = "Kec" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 20_000L, ACTOR);
        zones.setFreeShippingThreshold(0, ACTOR);
        return addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. T", "12160", null)).getPublicToken();
    }

    // ---------- AB-6D 三项 ----------

    @Test
    @DisplayName("默认单笔上限为 Rp 1.000.000（AC 指定的默认值）")
    void defaultCapIsOneMillion() {
        assertThat(admin.current().getMaxCoinPerOrder()).isEqualTo(1_000_000L);
        assertThat(admin.current().isEnabled()).isTrue();
        assertThat(admin.current().isAllowShippingDeduction()).isTrue();
    }

    @Test
    @DisplayName("🔗 改配置后下单行为随之变化（总开关关闭 → 新单变纯现金）")
    void configChangeAffectsNewOrders() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        String addr = seedAddress(uid);
        // 用真实的钱包服务充值 —— 直接 INSERT 会绕过双分录不变量，
        // 而且我第一次就把表结构写错了（pawcoin_wallets 没有 created_at）。
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "seed:" + SEQ.incrementAndGet());

        carts.add(uid, sku, 1);
        ShopOrder withCoin = checkout.placeOrder(uid, addr, "TOKO", null);
        assertThat(withCoin.getCoinAmount()).as("默认开启时应有 Coin 段").isPositive();

        admin.update(false, true, 1_000_000L, ACTOR);

        carts.add(uid, sku, 1);
        ShopOrder afterOff = checkout.placeOrder(uid, addr, "TOKO", null);
        assertThat(afterOff.getCoinAmount()).as("总开关关闭 → 新单纯现金").isZero();

        // 🔴 S-5：只影响新下单，【不影响已付款订单】——旧单的拆分是下单时固化的，一个数都不该动
        assertThat(withCoin.getCoinAmount())
                .as("关开关不得改写既有订单的拆分，否则就是对已付款用户违约（FR-100A 规则 5）")
                .isPositive();

        admin.update(true, true, 1_000_000L, ACTOR);   // 还原
    }

    @Test
    @DisplayName("运费开关关闭 → 运费不参与抵扣（现金段至少覆盖运费）")
    void shippingSwitchAffectsDeduction() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        String addr = seedAddress(uid);
        // 用真实的钱包服务充值 —— 直接 INSERT 会绕过双分录不变量，
        // 而且我第一次就把表结构写错了（pawcoin_wallets 没有 created_at）。
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "seed:" + SEQ.incrementAndGet());

        admin.update(true, false, 1_000_000L, ACTOR);
        carts.add(uid, sku, 1);
        ShopOrder o = checkout.placeOrder(uid, addr, "TOKO", null);

        assertThat(o.getCashAmount())
                .as("运费需用 QRIS 支付")
                .isGreaterThanOrEqualTo(o.getShippingFee());
        admin.update(true, true, 1_000_000L, ACTOR);
    }

    // ---------- 🔴🔴 两条溢价必须独立（C-9 / D-8） ----------

    @Test
    @DisplayName("🔴 激励溢价与平台责任补偿溢价【独立读写、互不影响】")
    void twoPremiumsAreIndependent() {
        var before = pawcoinConfig.findAll().getFirst();
        int incentiveRate = before.getPremiumRate();
        long incentiveFixed = before.getPremiumFixed();

        admin.updateCompensationPremium(15, 50_000L, ACTOR);

        var after = pawcoinConfig.findAll().getFirst();
        assertThat(after.getCompensationPremiumRate()).isEqualTo(15);
        assertThat(after.getCompensationPremiumCap()).isEqualTo(50_000L);
        // 🔴 写成单值会【静默】毁掉 AB-13A 售后成本口径与 AB-6C 浮存归因：
        //    不报错，只是两个报表的数字一直不对，且没人知道该信哪个。
        assertThat(after.getPremiumRate()).as("激励溢价不得被补偿溢价带动").isEqualTo(incentiveRate);
        assertThat(after.getPremiumFixed()).isEqualTo(incentiveFixed);

        admin.updateCompensationPremium(0, 0L, ACTOR);
    }

    @Test
    @DisplayName("补偿溢价参数校验：比例 0–100、上限非负")
    void compensationPremiumValidated() {
        assertThatThrownBy(() -> admin.updateCompensationPremium(101, 0L, ACTOR))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> admin.updateCompensationPremium(-1, 0L, ACTOR))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> admin.updateCompensationPremium(10, -1L, ACTOR))
                .isInstanceOf(AppException.class);
    }

    // ---------- 🔴 AB-6C 口径方向（L-7 / L-13） ----------

    @Test
    @DisplayName("🔴 浮存监控口径写的是「消费加快核销 → 浮存下降」，绝不是「充值推高浮存」")
    void floatMonitorCopyHasCorrectDirection() {
        String copy = AdminShopPawcoinRulesService.FLOAT_MONITOR_COPY;
        assertThat(copy).contains("加快").contains("浮存下降");
        // 方向写反会让运营在浮存告警时做出【恰好相反】的处置
        assertThat(copy).doesNotContain("推高浮存");
        // 处置建议是收紧溢价，不是下调单笔上限（后者只会把大额单挤到纯现金）
        assertThat(copy).contains("溢价").doesNotContain("下调单笔上限，");
    }

    @Test
    @DisplayName("单笔上限为负 → 拒绝")
    void negativeCapRejected() {
        assertThatThrownBy(() -> admin.update(true, true, -1L, ACTOR))
                .isInstanceOf(AppException.class);
    }
}

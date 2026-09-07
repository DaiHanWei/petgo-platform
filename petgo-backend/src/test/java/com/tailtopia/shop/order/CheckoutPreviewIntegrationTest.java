package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.dto.CheckoutPreviewView;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：结算页试算（Story 3.7 后端）。
 *
 * <p>看住的是**结算页展示口径**：两段金额、单笔上限截断提示、超范围不整页报错、
 * 多 SKU 取最严退货标识。金额计算本身归 Story 3.4 的 {@code CheckoutIntegrationTest}。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class CheckoutPreviewIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private CheckoutService checkout;
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
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "cp" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "cp" + n);
    }

    private String seedSku(long stock, long price, String productPolicy) {
        String pToken = "pp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', ?, true)
                """, pToken, productPolicy);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "ps" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)""", sToken, pid, price);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)",
                sid, stock);
        return sToken;
    }

    private String seedAddress(long uid, long fee) {
        String kec = "Kp" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", fee, ACTOR);
        return addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
    }

    /** 造余额走真实钱包 service（直接 INSERT 会绕过双分录不变量）。 */
    private void topUp(long uid, long coins) {
        wallet.credit(uid, coins, PawCoinTxnType.TOPUP, "TEST", null,
                "test-topup:" + uid + ":" + SEQ.incrementAndGet());
    }

    private CheckoutPreviewView preview(long uid, String addr) {
        return CheckoutPreviewView.of(checkout.preview(uid, addr));
    }

    // ---------- 🔴 FR-100A 规则 2：两段金额 ----------

    @Test
    @DisplayName("纯 QRIS：余额为 0 → coinAmount=0，全额现金")
    void pureCash() {
        long uid = seedUser();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, seedSku(10, 285_000L, "NO_RETURN_AFTER_OPEN"), 1);

        var v = preview(uid, addr);

        assertThat(v.payableTotal()).isEqualTo(305_000L);
        assertThat(v.coinAmount()).isZero();
        assertThat(v.cashAmount()).isEqualTo(305_000L);
        assertThat(v.coinCapped()).isFalse();
    }

    @Test
    @DisplayName("纯 Coin：余额足以覆盖全额 → cashAmount=0")
    void pureCoin() {
        long uid = seedUser();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, seedSku(10, 100_000L, "RETURNABLE"), 1);
        topUp(uid, 500_000L);

        var v = preview(uid, addr);

        assertThat(v.payableTotal()).isEqualTo(120_000L);
        assertThat(v.coinAmount()).isEqualTo(120_000L);
        assertThat(v.cashAmount()).isZero();
    }

    @Test
    @DisplayName("🔴 混合：余额不足不阻断，两段金额都下发（前端才能同时展示）")
    void mixedSplitIsExposedAsTwoSegments() {
        long uid = seedUser();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, seedSku(10, 350_000L, "RETURNABLE"), 1);
        topUp(uid, 60_000L);

        var v = preview(uid, addr);

        // 只回一个总数会逼前端自己拆，而拆分规则全在服务端
        assertThat(v.coinAmount()).isEqualTo(60_000L);
        assertThat(v.cashAmount()).isEqualTo(310_000L);
        assertThat(v.coinAmount() + v.cashAmount()).isEqualTo(v.payableTotal());
        assertThat(v.coinCapped()).as("没到上限就不该提示被截断").isFalse();
    }

    // ---------- 🔴 C-16 / UX-DR14：单笔上限截断必须可被前端识别 ----------

    @Test
    @DisplayName("🔴 余额够但撞单笔上限 → coinCapped=true（不明示用户会以为系统算错）")
    void coinCappedWhenBalanceExceedsPerOrderLimit() {
        long uid = seedUser();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 100_000L, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, seedSku(10, 500_000L, "RETURNABLE"), 1);
        topUp(uid, 400_000L);

        var v = preview(uid, addr);

        assertThat(v.coinAmount()).isEqualTo(100_000L);
        assertThat(v.maxCoinPerOrder()).isEqualTo(100_000L);
        assertThat(v.coinCapped()).isTrue();
    }

    @Test
    @DisplayName("🔴 余额恰等于上限 → 不算截断（相等 ≠ 被截断，多提示一行只会让用户困惑）")
    void balanceEqualToLimitIsNotCapped() {
        long uid = seedUser();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 100_000L, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, seedSku(10, 500_000L, "RETURNABLE"), 1);
        topUp(uid, 100_000L);

        var v = preview(uid, addr);

        assertThat(v.coinAmount()).isEqualTo(100_000L);
        assertThat(v.coinCapped()).isFalse();
    }

    @Test
    @DisplayName("总开关关闭 → 不用 Coin，也不提示截断")
    void coinDisabled() {
        long uid = seedUser();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(false, true, 1_000_000L, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, seedSku(10, 300_000L, "RETURNABLE"), 1);
        topUp(uid, 500_000L);

        var v = preview(uid, addr);

        assertThat(v.coinAmount()).isZero();
        assertThat(v.coinCapped()).isFalse();
        // 关闭开关不影响用户看到自己的余额（页面要解释「为什么没用上」）
        assertThat(v.coinBalance()).isEqualTo(500_000L);
    }

    // ---------- 🔴 FR-99：超服务范围是一个可渲染的状态，不是整页错误 ----------

    @Test
    @DisplayName("🔴 超服务范围 → serviceable=false 且金额位为 null，但地址与商品清单照常下发")
    void outOfRangeStillRendersAddressAndLines() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        String sku = seedSku(10, 285_000L, "RETURNABLE");
        carts.add(uid, sku, 1);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789",
                "DKI Jakarta", "Jakarta Selatan", "Nowhere" + SEQ.incrementAndGet(),
                "Jl. X", "12160", null)).getPublicToken();

        var v = preview(uid, addr);

        assertThat(v.serviceable()).isFalse();
        assertThat(v.payableTotal()).as("算不出应付就不许编一个").isNull();
        assertThat(v.shippingFee()).isNull();
        // 用户得看见自己选的是哪个地址、车里有什么，否则不知道该改哪里
        assertThat(v.address().token()).isEqualTo(addr);
        assertThat(v.lines()).hasSize(1);
        assertThat(v.goodsSubtotal()).isEqualTo(285_000L);
    }

    // ---------- 🔴 FR-104 / S-6：多 SKU 取最严 ----------

    @Test
    @DisplayName("🔴 混装取最严：可退 + 开封不退 → 整单标识为开封不退")
    void strictestOfMixedPolicies() {
        long uid = seedUser();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, seedSku(10, 100_000L, "RETURNABLE"), 1);
        carts.add(uid, seedSku(10, 100_000L, "NO_RETURN_AFTER_OPEN"), 1);

        var v = preview(uid, addr);

        assertThat(v.strictestReturnPolicy()).isEqualTo("NO_RETURN_AFTER_OPEN");
        // 逐行标识仍要给，用户可展开看是哪一件不能退
        assertThat(v.lines()).extracting(CheckoutPreviewView.CheckoutLine::returnPolicy)
                .containsExactlyInAnyOrder("RETURNABLE", "NO_RETURN_AFTER_OPEN");
    }

    @Test
    @DisplayName("🔴 含不可退商品 → 整单取 NON_RETURNABLE（宁可少承诺）")
    void nonReturnableWins() {
        long uid = seedUser();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, seedSku(10, 100_000L, "RETURNABLE"), 1);
        carts.add(uid, seedSku(10, 100_000L, "NON_RETURNABLE"), 1);

        var v = preview(uid, addr);

        assertThat(v.strictestReturnPolicy()).isEqualTo("NON_RETURNABLE");
    }

    // ---------- 🔴 FR-97：不含也不预留优惠体系字段 ----------

    @Test
    @DisplayName("🔴 结算视图不含优惠券/促销码/会员价字段（会员制暂缓，不提前埋成本）")
    void noPromotionFieldsAtAll() {
        // 断言的是 record 组件本身，不是某次响应的内容 —— 只要有人加了字段就会红，
        // 而 FR-97 要挡的正是「先把字段留着，以后再说」。
        var names = Arrays.stream(CheckoutPreviewView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(n -> n.toLowerCase(Locale.ROOT))
                .toList();
        assertThat(names).noneMatch(n -> n.contains("coupon") || n.contains("promo")
                || n.contains("voucher") || n.contains("member") || n.contains("discountcode"));
        // 运费抵扣是 FR-99 的免运，不是优惠体系 —— 它必须在
        assertThat(names).contains("shippingdiscount");
    }

    @Test
    @DisplayName("配送方式恒为唯一档 REGULER（C-14 已把二维表降为一维）")
    void singleShippingMethod() {
        long uid = seedUser();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, seedSku(10, 100_000L, "RETURNABLE"), 1);

        assertThat(preview(uid, addr).shippingMethod()).isEqualTo("REGULER");
    }

    // ---------- HTTP 层（Story 3.7 新增的两个端点）----------

    @Test
    @DisplayName("🔒 游客读结算试算 → 401（/me 前缀本就受保护，本 story 未开任何放行）")
    void guestCannotPreview() throws Exception {
        mvc.perform(get("/api/v1/me/checkout").param("addressToken", "whatever"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /me/checkout 下发两段金额与最严退货标识")
    void previewOverHttp() throws Exception {
        var u = newUser();
        long uid = u.getId();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, seedSku(10, 350_000L, "NO_RETURN_AFTER_OPEN"), 1);
        topUp(uid, 60_000L);

        mvc.perform(get("/api/v1/me/checkout")
                        .param("addressToken", addr)
                        .header("Authorization", userBearer(uid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coinAmount").value(60_000))
                .andExpect(jsonPath("$.cashAmount").value(310_000))
                .andExpect(jsonPath("$.payableTotal").value(370_000))
                .andExpect(jsonPath("$.strictestReturnPolicy").value("NO_RETURN_AFTER_OPEN"))
                .andExpect(jsonPath("$.serviceable").value(true));
    }

    @Test
    @DisplayName("POST /me/shop-orders 建单 → 201 + 不可枚举 orderToken（绝不下发自增 id/seq_no）")
    void placeOrderOverHttp() throws Exception {
        var u = newUser();
        long uid = u.getId();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, seedSku(10, 285_000L, "RETURNABLE"), 1);

        mvc.perform(post("/api/v1/me/shop-orders")
                        .header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"addressToken":"%s","entrySource":"TOKO_ALL_FEATURED"}"""
                                .formatted(addr)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderToken").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(305_000))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.seqNo").doesNotExist());
    }

    @Test
    @DisplayName("🔴 有行不可购买 → 409 + unavailableLines 逐行明细（不整单打回）")
    void placeOrderReturnsPerLineDetail() throws Exception {
        var u = newUser();
        long uid = u.getId();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        String tight = seedSku(5, 200_000L, "RETURNABLE");
        carts.add(uid, tight, 5);
        // 加购后被别人买走 3 件
        jdbc.update("UPDATE sku_inventory SET actual = 2 WHERE sku_id = "
                + "(SELECT id FROM shop_skus WHERE public_token = ?)", tight);

        mvc.perform(post("/api/v1/me/shop-orders")
                        .header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"addressToken":"%s"}""".formatted(addr)))
                .andExpect(status().isConflict())
                // 笼统的「库存不足，请重试」会让用户在一车商品里逐个试错
                .andExpect(jsonPath("$.unavailableLines[0].skuToken").value(tight))
                .andExpect(jsonPath("$.unavailableLines[0].available").value(2))
                .andExpect(jsonPath("$.unavailableLines[0].requested").value(5))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("缺 addressToken → 422，且不外泄堆栈")
    void placeOrderWithoutAddress() throws Exception {
        var u = newUser();
        mvc.perform(post("/api/v1/me/shop-orders")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("请选择收货地址"));
    }
}

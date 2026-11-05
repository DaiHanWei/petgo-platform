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
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import com.tailtopia.shop.coupon.service.CouponAdminService;
import com.tailtopia.shop.coupon.service.CouponDraft;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：新结算试算 {@code POST /api/v1/me/checkout/preview} 与购物车 {@code estimate}（v1.4.2 shop Story 3.1
 * AC1 / AC3 / AC7）。需 Docker postgres + redis，{@code BITESHIP_MODE=stub}（JNE 10,000 支持保价 / SiCepat 12,000）。
 * 云端不跑，待本地验收。
 */
class CheckoutQuoteIntegrationTest extends ApiIntegrationTest {

    @Autowired private CartService carts;
    @Autowired private ShippingAddressService addresses;
    @Autowired private AdminShippingZoneService zones;
    @Autowired private AdminShopPawcoinRulesService rules;
    @Autowired private PawCoinWalletService wallet;
    @Autowired private CouponAdminService couponAdmin;
    @Autowired private CouponLedgerService ledger;
    @Autowired private ShopCouponRepository couponRepo;
    @Autowired private UserCouponRepository userCoupons;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private String seedSku(long stock, long price) {
        String pToken = "cq" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?",
                Long.class, pToken);
        String sToken = "cs" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, package_weight_g,
                        package_length_cm, package_width_cm, package_height_cm)
                VALUES (?, ?, '3 kg', ?, 1000, 20, 15, 10)""", sToken, pid, price);
        Long sid = jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?",
                Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)", sid, stock);
        return sToken;
    }

    /** 开通区域 + 本人默认地址（首个地址自动默认）。 */
    private String seedDefaultAddress(long uid) {
        String kec = "Kq" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        return addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
    }

    private UserCoupon issue(long uid, CouponKind kind, long amount, long minSpend) {
        Instant now = Instant.now();
        ShopCoupon c = couponAdmin.create(new CouponDraft(kind, "Kupon IT", "Coupon IT", amount,
                minSpend, CouponScopeType.ALL, null, CouponAcquireType.CLAIM,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(10)),
                CouponValidMode.RELATIVE, null, null, 7, null, 5), null);
        return tx.execute(s -> {
            ShopCoupon locked = couponRepo.findLockedById(c.getId()).orElseThrow();
            couponRepo.incrementIssuedIfAvailable(locked.getId());
            return ledger.issue(uid, locked, UserCouponSource.CLAIM, Instant.now());
        });
    }

    private String body(String json) {
        return json;
    }

    @Test
    @DisplayName("默认地址 + stub 两家报价 → 升序、默认最便宜、quoteToken 非空")
    void defaultAddressCheapestCourier() throws Exception {
        long uid = newUser().getId();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        seedDefaultAddress(uid);
        carts.add(uid, seedSku(10, 100_000L), 1);

        mvc.perform(post("/api/v1/me/checkout/preview").header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"CART\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteToken").isNotEmpty())
                .andExpect(jsonPath("$.couriers[0].price").value(10_000))
                .andExpect(jsonPath("$.couriers[1].price").value(12_000))
                .andExpect(jsonPath("$.selectedCourierKey").value("jne:reg"))
                .andExpect(jsonPath("$.pricing.shippingIncluded").value(true))
                .andExpect(jsonPath("$.pricing.payable").value(110_000))
                .andExpect(jsonPath("$.placeable").value(true));
    }

    @Test
    @DisplayName("BUY_NOW：购物车行与勾选状态在试算前后逐行相同")
    void buyNowLeavesCartUntouched() throws Exception {
        long uid = newUser().getId();
        seedDefaultAddress(uid);
        String inCart = seedSku(10, 50_000L);
        carts.add(uid, inCart, 2);
        carts.setSelected(uid, inCart, false);
        CartView before = carts.view(uid);
        String buy = seedSku(10, 80_000L);

        mvc.perform(post("/api/v1/me/checkout/preview").header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"source\":\"BUY_NOW\",\"buyNow\":{\"skuToken\":\"%s\",\"qty\":1}}"
                                .formatted(buy))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].skuToken").value(buy))
                .andExpect(jsonPath("$.pricing.goodsAmount").value(80_000));

        assertThat(carts.view(uid)).isEqualTo(before);
    }

    @Test
    @DisplayName("满减 + 免运券 AUTO 选最优；MANUAL 未达门槛 → 不采用、unavailable 带 BELOW_MIN_SPEND；试算只读")
    void couponsAndReadOnlyInvariants() throws Exception {
        long uid = newUser().getId();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        seedDefaultAddress(uid);
        String sku = seedSku(10, 100_000L);
        carts.add(uid, sku, 1);
        wallet.credit(uid, 5_000L, PawCoinTxnType.TOPUP, "TEST", null, "t:" + SEQ.incrementAndGet());
        UserCoupon disc = issue(uid, CouponKind.DISCOUNT, 8_000L, 0L);
        UserCoupon high = issue(uid, CouponKind.DISCOUNT, 20_000L, 500_000L);
        UserCoupon fs = issue(uid, CouponKind.FREE_SHIPPING, 15_000L, 0L);
        long lockedBefore = jdbc.queryForObject("SELECT locked FROM sku_inventory WHERE sku_id = "
                + "(SELECT id FROM shop_skus WHERE public_token = ?)", Long.class, sku);

        mvc.perform(post("/api/v1/me/checkout/preview").header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"CART\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupons.discount.selectedToken").value(disc.getPublicToken()))
                .andExpect(jsonPath("$.coupons.freeShipping.selectedToken").value(fs.getPublicToken()))
                .andExpect(jsonPath("$.pricing.savedAmount").value(18_000));

        mvc.perform(post("/api/v1/me/checkout/preview").header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"CART\",\"couponMode\":\"MANUAL\",\"discountCouponToken\":\"%s\"}"
                                .formatted(high.getPublicToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupons.discount.selectedToken").doesNotExist())
                .andExpect(jsonPath("$.coupons.discount.unavailable[?(@.userCouponToken == '%s')].unavailableReason"
                        .formatted(high.getPublicToken())).value("BELOW_MIN_SPEND"));

        for (UserCoupon uc : List.of(disc, high, fs)) {
            assertThat(userCoupons.findById(uc.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserCouponStatus.AVAILABLE);
        }
        assertThat(wallet.balanceOf(uid)).isEqualTo(5_000L);
        assertThat(jdbc.queryForObject("SELECT locked FROM sku_inventory WHERE sku_id = "
                + "(SELECT id FROM shop_skus WHERE public_token = ?)", Long.class, sku))
                .isEqualTo(lockedBefore);
    }

    @Test
    @DisplayName("被待付款订单 LOCKED 的券不出现在任何组")
    void lockedCouponIsInvisible() throws Exception {
        long uid = newUser().getId();
        seedDefaultAddress(uid);
        carts.add(uid, seedSku(10, 100_000L), 1);
        UserCoupon uc = issue(uid, CouponKind.DISCOUNT, 8_000L, 0L);
        long order = jdbc.queryForObject("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING_PAYMENT', 100000, 0, 0, 100000,
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110', now(), now())
                RETURNING id""", Long.class, "cqo" + SEQ.incrementAndGet(),
                "TOKO-CQ-" + SEQ.incrementAndGet() % 100_000_000, uid);
        tx.executeWithoutResult(s -> ledger.lock(uid, order, List.of(uc.getPublicToken()), Instant.now()));

        String res = mvc.perform(post("/api/v1/me/checkout/preview")
                        .header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"CART\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(res).doesNotContain(uc.getPublicToken());
    }

    @Test
    @DisplayName("🔒 兽医 token → 403；无 token → 401")
    void securityRules() throws Exception {
        mvc.perform(post("/api/v1/me/checkout/preview").header("Authorization", vetBearer(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"CART\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/checkout/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"CART\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("复审修复：无购物车用户 POST /me/checkout/preview → 200 NO_LINES，且不创建 shop_carts 行")
    void noCartUserPreview() throws Exception {
        long uid = newUser().getId();
        mvc.perform(post("/api/v1/me/checkout/preview").header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"CART\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notPlaceableReason").value("NO_LINES"))
                .andExpect(jsonPath("$.placeable").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_carts WHERE user_id = ?",
                Long.class, uid)).isZero();
    }

    @Test
    @DisplayName("复审修复：库内勾着的失效行 → UNAVAILABLE_LINES（不静默少买）")
    void dbSelectedInvalidLineBlocksNewPreview() throws Exception {
        long uid = newUser().getId();
        seedDefaultAddress(uid);
        String good = seedSku(10, 100_000L);
        String dead = seedSku(10, 50_000L);
        carts.add(uid, good, 1);
        carts.add(uid, dead, 1);
        jdbc.update("UPDATE sku_inventory SET actual = 0 WHERE sku_id = "
                + "(SELECT id FROM shop_skus WHERE public_token = ?)", dead);
        mvc.perform(post("/api/v1/me/checkout/preview").header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"CART\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notPlaceableReason").value("UNAVAILABLE_LINES"))
                .andExpect(jsonPath("$.lines[1].unavailableReason").value("OUT_OF_STOCK"));
    }

    @Test
    @DisplayName("source 非法 → 422")
    void invalidSource() throws Exception {
        long uid = newUser().getId();
        mvc.perform(post("/api/v1/me/checkout/preview").header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"NOPE\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 购物车 estimate（AC3） ----------

    @Test
    @DisplayName("GET /me/cart：默认地址 → estimate.lowestFee=10000；无地址无地区 → NO_DESTINATION；一件没选 → NO_SELECTION")
    void cartEstimateReasons() throws Exception {
        long uid = newUser().getId();
        zones.setFreeShippingThreshold(0, ACTOR);
        String sku = seedSku(10, 100_000L);
        carts.add(uid, sku, 1);

        mvc.perform(get("/api/v1/me/cart").header("Authorization", userBearer(uid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimate.reason").value("NO_DESTINATION"))
                .andExpect(jsonPath("$.estimate.lowestFee").doesNotExist())
                .andExpect(jsonPath("$.estimate.payable").value(100_000));

        seedDefaultAddress(uid);
        mvc.perform(get("/api/v1/me/cart").header("Authorization", userBearer(uid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimate.lowestFee").value(10_000))
                .andExpect(jsonPath("$.estimate.payable").value(110_000))
                .andExpect(jsonPath("$.estimate.reason").doesNotExist());

        carts.setAllSelected(uid, false);
        mvc.perform(get("/api/v1/me/cart").header("Authorization", userBearer(uid)))
                .andExpect(jsonPath("$.estimate.reason").value("NO_SELECTION"))
                .andExpect(jsonPath("$.estimate.payable").value(0));
    }
}

package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
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
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.dto.PlaceOrderRequest;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutQuoteService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.PlaceCommand;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：提交订单（v1.4.2 shop Story 3.2 AC3 / AC4 / AC5 / AC6 / AC8）。需 Docker postgres + redis，
 * {@code BITESHIP_MODE=stub}（JNE 10,000 支持保价 / SiCepat 12,000）。云端不跑，待本地验收。
 */
class CheckoutPlaceIntegrationTest extends ApiIntegrationTest {

    @Autowired private CartService carts;
    @Autowired private ShippingAddressService addresses;
    @Autowired private AdminShippingZoneService zones;
    @Autowired private AdminShopPawcoinRulesService rules;
    @Autowired private PawCoinWalletService wallet;
    @Autowired private CouponAdminService couponAdmin;
    @Autowired private CouponLedgerService ledger;
    @Autowired private ShopCouponRepository couponRepo;
    @Autowired private UserCouponRepository userCoupons;
    @Autowired private CheckoutQuoteService quotes;
    @Autowired private CheckoutService checkout;
    @Autowired private ShopOrderPaymentService payments;
    @Autowired private ShopOrderRepository orders;
    @Autowired private ShopOrderLineRepository orderLines;
    @Autowired private SkuInventoryRepository inventory;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private String seedSku(long stock, long price) {
        String pToken = "pl" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?",
                Long.class, pToken);
        String sToken = "ps" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, package_weight_g,
                        package_length_cm, package_width_cm, package_height_cm)
                VALUES (?, ?, '3 kg', ?, 1000, 20, 15, 10)""", sToken, pid, price);
        Long sid = jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?",
                Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)", sid, stock);
        return sToken;
    }

    private long skuId(String token) {
        return jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class, token);
    }

    private String seedAddress(long uid, String kodePos) {
        String kec = "Kp" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        return addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", kodePos, "Rumah")).getPublicToken();
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

    private void topUp(long uid, long coins) {
        wallet.credit(uid, coins, PawCoinTxnType.TOPUP, "TEST", null, "pl-topup:" + SEQ.incrementAndGet());
    }

    private String quoteTokenFor(long uid, String source, CheckoutPreviewRequest.BuyNow buyNow,
            String addr) {
        return quotes.preview(uid, new CheckoutPreviewRequest(source, buyNow, addr, null, false,
                "MANUAL", null, null, true)).quoteToken();
    }

    private ShopOrder placeCart(long uid, String addr, String quoteToken, String discount,
            String freeShipping, boolean useCoin) {
        return checkout.place(uid, PlaceCommand.of(new PlaceOrderRequest(addr, "SHOP_HOME", null,
                "CART", null, quoteToken, "jne:reg", false, discount, freeShipping, useCoin, true)),
                null);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    @DisplayName("新模式 CART：PENDING_PAYMENT、库存锁、券 LOCKED、币已扣（SPEND/SHOP_ORDER）、快递五列与报价 id、已下单行离开购物车")
    void cartHappyPath() {
        long uid = newUser().getId();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        topUp(uid, 30_000L);
        String addr = seedAddress(uid, "12160");
        String sku = seedSku(10, 100_000L);
        carts.add(uid, sku, 2);
        UserCoupon disc = issue(uid, CouponKind.DISCOUNT, 10_000L, 0L);

        ShopOrder o = placeCart(uid, addr, quoteTokenFor(uid, "CART", null, addr),
                disc.getPublicToken(), null, true);

        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        assertThat(o.getCheckoutSource()).isEqualTo(CheckoutSource.CART);
        assertThat(o.getGoodsSubtotal()).isEqualTo(200_000L);
        assertThat(o.getDiscountCouponAmount()).isEqualTo(10_000L);
        assertThat(o.getShippingFee()).isEqualTo(10_000L);
        assertThat(o.getTotalAmount()).isEqualTo(200_000L);
        assertThat(o.getCoinAmount()).isEqualTo(30_000L);
        assertThat(o.getCourierCode()).isEqualTo("jne");
        assertThat(o.getShippingQuoteId()).isNotNull();
        assertThat(wallet.balanceOf(uid)).isZero();
        assertThat(count("SELECT count(*) FROM pawcoin_transactions WHERE user_id = ? AND type = 'SPEND' "
                + "AND ref_type = 'SHOP_ORDER'", uid)).isEqualTo(1L);
        assertThat(userCoupons.findById(disc.getId()).orElseThrow().getStatus())
                .isEqualTo(UserCouponStatus.LOCKED);
        assertThat(inventory.findBySkuId(skuId(sku)).orElseThrow().getLocked()).isEqualTo(2L);
        assertThat(carts.view(uid).lines()).isEmpty();
        // 返佣基数：unit_price = SKU 原价，goods_subtotal 为券前
        assertThat(orderLines.findByOrderIdOrderByIdAsc(o.getId()))
                .allSatisfy(l -> assertThat(l.getUnitPrice()).isEqualTo(100_000L));
    }

    @Test
    @DisplayName("BUY_NOW 纯币足额：提交即 PENDING_SHIPMENT、paid_at 非空、券 USED；购物车不变")
    void buyNowPureCoin() {
        long uid = newUser().getId();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        topUp(uid, 500_000L);
        String addr = seedAddress(uid, "12160");
        String inCart = seedSku(10, 50_000L);
        carts.add(uid, inCart, 1);
        String sku = seedSku(10, 80_000L);
        UserCoupon disc = issue(uid, CouponKind.DISCOUNT, 5_000L, 0L);
        var buyNow = new CheckoutPreviewRequest.BuyNow(sku, 1, "PRODUCT_DETAIL");

        ShopOrder o = checkout.place(uid, PlaceCommand.of(new PlaceOrderRequest(addr, null, null,
                "BUY_NOW", buyNow, quoteTokenFor(uid, "BUY_NOW", buyNow, addr), "jne:reg", false,
                disc.getPublicToken(), null, true, true)), null);

        ShopOrder after = orders.findById(o.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        assertThat(after.getPaidAt()).isNotNull();
        assertThat(after.getCashAmount()).isZero();
        assertThat(userCoupons.findById(disc.getId()).orElseThrow().getStatus())
                .isEqualTo(UserCouponStatus.USED);
        assertThat(carts.view(uid).lines()).singleElement()
                .satisfies(l -> assertThat(l.skuToken()).isEqualTo(inCart));
    }

    @Test
    @DisplayName("🔴 报价绑定：A 地址的 quoteToken 提交 B 地址（不同邮编）→ 409；改购物车数量后旧 token → 409")
    void quoteBoundToAddressAndItems() {
        long uid = newUser().getId();
        String a = seedAddress(uid, "12160");
        String b = seedAddress(uid, "10110");
        String sku = seedSku(10, 100_000L);
        carts.add(uid, sku, 1);
        String token = quoteTokenFor(uid, "CART", null, a);

        assertThatThrownBy(() -> placeCart(uid, b, token, null, null, false))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.QUOTE_EXPIRED));

        carts.add(uid, sku, 1);   // 数量 1 → 2
        assertThatThrownBy(() -> placeCart(uid, a, token, null, null, false))
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.QUOTE_EXPIRED));
        assertThat(count("SELECT count(*) FROM shop_orders WHERE user_id = ?", uid)).isZero();
    }

    @Test
    @DisplayName("🔴 同一张券两单并发提交 → 恰一单成功，另一单 409 coupon-invalid；失败那单币与库存全部回滚")
    void concurrentSameCoupon() throws Exception {
        long uid = newUser().getId();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        topUp(uid, 20_000L);
        String addr = seedAddress(uid, "12160");
        String sku = seedSku(10, 100_000L);
        UserCoupon disc = issue(uid, CouponKind.DISCOUNT, 10_000L, 0L);
        var buyNow = new CheckoutPreviewRequest.BuyNow(sku, 1, null);
        String token = quoteTokenFor(uid, "BUY_NOW", buyNow, addr);

        Callable<Object> task = () -> {
            try {
                return checkout.place(uid, PlaceCommand.of(new PlaceOrderRequest(addr, null, null,
                        "BUY_NOW", buyNow, token, "jne:reg", false, disc.getPublicToken(), null,
                        false, true)), null);
            } catch (AppException e) {
                return e;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Object>> fs = new ArrayList<>(pool.invokeAll(List.of(task, task)));
        pool.shutdown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : fs) {
            results.add(f.get());
        }
        assertThat(results).filteredOn(r -> r instanceof ShopOrder).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof AppException)
                .singleElement().satisfies(e -> assertThat(((AppException) e).getType())
                        .isIn(ErrorTypes.COUPON_INVALID, ErrorTypes.CONFLICT));
        assertThat(wallet.balanceOf(uid)).as("useCoin=false，余额不动").isEqualTo(20_000L);
        assertThat(inventory.findBySkuId(skuId(sku)).orElseThrow().getLocked()).isEqualTo(1L);
    }

    @Test
    @DisplayName("🔴 用户取消待付款单 → 币退回（REFUND/SHOP_ORDER_VOID）、券回 AVAILABLE、库存释放、cancel_reason")
    void cancelReturnsCoinAndCoupon() {
        long uid = newUser().getId();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        topUp(uid, 30_000L);
        String addr = seedAddress(uid, "12160");
        String sku = seedSku(10, 100_000L);
        carts.add(uid, sku, 1);
        UserCoupon disc = issue(uid, CouponKind.DISCOUNT, 10_000L, 0L);
        ShopOrder o = placeCart(uid, addr, quoteTokenFor(uid, "CART", null, addr),
                disc.getPublicToken(), null, true);

        payments.cancel(uid, o.getPublicToken());

        assertThat(wallet.balanceOf(uid)).isEqualTo(30_000L);
        assertThat(userCoupons.findById(disc.getId()).orElseThrow().getStatus())
                .isEqualTo(UserCouponStatus.AVAILABLE);
        assertThat(inventory.findBySkuId(skuId(sku)).orElseThrow().getLocked()).isZero();
        ShopOrder after = orders.findById(o.getId()).orElseThrow();
        assertThat(after.getCancelReason()).isEqualTo(CancelReason.USER_UNPAID_CANCEL);
        assertThat(count("SELECT count(*) FROM pawcoin_transactions WHERE user_id = ? AND type = 'REFUND' "
                + "AND ref_type = 'SHOP_ORDER_VOID'", uid)).isEqualTo(1L);
    }

    @Test
    @DisplayName("旧模式（不带 quoteToken）：checkout_source=LEGACY、运费 = stub 最低价、无券、币按余额抵扣；与旧预览运费一致")
    void legacyModeOverHttp() throws Exception {
        long uid = newUser().getId();
        zones.setFreeShippingThreshold(0, ACTOR);
        rules.update(true, true, 1_000_000L, ACTOR);
        topUp(uid, 20_000L);
        String addr = seedAddress(uid, "12160");
        carts.add(uid, seedSku(10, 100_000L), 1);
        Long previewFee = checkout.preview(uid, addr).shippingFee();

        mvc.perform(post("/api/v1/me/shop-orders").header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressToken\":\"" + addr + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shippingFee").value(10_000))
                .andExpect(jsonPath("$.coinAmount").value(20_000))
                .andExpect(jsonPath("$.discountCouponAmount").value(0))
                .andExpect(jsonPath("$.paid").value(false));

        assertThat(previewFee).isEqualTo(10_000L);
        assertThat(jdbc.queryForObject("SELECT checkout_source FROM shop_orders WHERE user_id = ?",
                String.class, uid)).isEqualTo("LEGACY");
        assertThat(wallet.balanceOf(uid)).isZero();
    }

    @Test
    @DisplayName("新模式 HTTP：agreeReturnPolicy 缺失 → 422；quoteToken 不存在 → 409 quote-expired")
    void newModeErrorsOverHttp() throws Exception {
        long uid = newUser().getId();
        String addr = seedAddress(uid, "12160");
        carts.add(uid, seedSku(10, 100_000L), 1);

        mvc.perform(post("/api/v1/me/shop-orders").header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressToken\":\"" + addr + "\",\"source\":\"CART\",\"quoteToken\":\"nope\","
                                + "\"courierKey\":\"jne:reg\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/me/shop-orders").header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressToken\":\"" + addr + "\",\"source\":\"CART\",\"quoteToken\":\"nope\","
                                + "\"courierKey\":\"jne:reg\",\"agreeReturnPolicy\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorTypes.QUOTE_EXPIRED.toString()));
    }
}

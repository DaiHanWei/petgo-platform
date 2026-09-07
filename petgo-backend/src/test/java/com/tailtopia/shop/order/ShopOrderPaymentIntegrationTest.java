package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：电商订单支付（Story 3.8，FR-100 / AD-8 / AD-9）。
 *
 * <p>🔴 本类看的是**钱与货的一致性**：重复回调只扣一次库存、超时必须把库存还回去、
 * 纯 PawCoin 单当场结清、支付窗以服务端时刻为准。这些错了都是真实损失，不是显示问题。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class ShopOrderPaymentIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private CheckoutService checkout;
    @Autowired
    private ShopOrderPaymentService payments;
    @Autowired
    private PaymentIntentService paymentIntents;
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
    private SkuInventoryRepository inventory;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "pay" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class,
                "pay" + n);
    }

    private String seedSku(long stock, long price) {
        String pToken = "yp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "ys" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)""", sToken, pid, price);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)",
                sid, stock);
        return sToken;
    }

    private long skuId(String token) {
        return jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class,
                token);
    }

    private String seedAddress(long uid, long fee) {
        String kec = "Kpay" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", fee, ACTOR);
        return addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
    }

    private void topUp(long uid, long coins) {
        wallet.credit(uid, coins, PawCoinTxnType.TOPUP, "TEST", null,
                "pay-topup:" + uid + ":" + SEQ.incrementAndGet());
    }

    /** 下单一单（默认无 PawCoin 抵扣、运费 0）。 */
    private ShopOrder placeOrder(long uid, String sku, int qty, long price) {
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 0L);
        carts.add(uid, sku, qty);
        return checkout.placeOrder(uid, addr, null, null);
    }

    private void payCallback(String intentToken) {
        paymentIntents.applyCallback(new PaymentCallback(intentToken,
                "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID, Map.of()));
    }

    // ---------- 纯 PawCoin：当场结清 ----------

    @Test
    @DisplayName("🔴 纯 PawCoin 单：不建支付意图，当场扣币 + 扣库存 + 转待发货")
    void pureCoinSettlesImmediately() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        topUp(uid, 500_000L);
        String sku = seedSku(10, 100_000L);
        ShopOrder order = placeOrder(uid, sku, 1, 100_000L);
        assertThat(order.getPayChannel()).isEqualTo(PayChannel.PAWCOIN);

        var result = payments.pay(uid, order.getPublicToken(), null);

        assertThat(result.orderStatus()).isEqualTo("PENDING_SHIPMENT");
        // 没有真钱环节就没有网关意图（与咨询/身份证同范式）
        assertThat(result.paymentIntentToken()).isNull();
        assertThat(result.payload()).isNull();
        assertThat(wallet.balanceOf(uid)).isEqualTo(400_000L);
        var inv = inventory.findBySkuId(skuId(sku)).orElseThrow();
        assertThat(inv.getActual()).as("支付成功 → 锁定转扣减").isEqualTo(9L);
        assertThat(inv.getLocked()).isZero();
    }

    @Test
    @DisplayName("纯 PawCoin 单重复点支付 → 第二次拿到「已不在待支付状态」，币不会被扣两次")
    void pureCoinPayTwiceDoesNotDoubleCharge() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        topUp(uid, 500_000L);
        String sku = seedSku(10, 100_000L);
        ShopOrder order = placeOrder(uid, sku, 1, 100_000L);

        payments.pay(uid, order.getPublicToken(), null);
        assertThatThrownBy(() -> payments.pay(uid, order.getPublicToken(), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("待支付");

        assertThat(wallet.balanceOf(uid)).isEqualTo(400_000L);
    }

    // ---------- QRIS / 混合：到账推进 ----------

    @Test
    @DisplayName("纯 QRIS 单：建 QRIS 意图 → 到账后扣库存 + 转待发货")
    void qrisPaymentFulfillsOnCallback() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        String sku = seedSku(10, 285_000L);
        ShopOrder order = placeOrder(uid, sku, 1, 285_000L);
        assertThat(order.getPayChannel()).isEqualTo(PayChannel.QRIS);

        var result = payments.pay(uid, order.getPublicToken(), null);
        assertThat(result.paymentIntentToken()).isNotNull();
        assertThat(result.orderStatus()).isEqualTo("PENDING_PAYMENT");

        payCallback(result.paymentIntentToken());

        ShopOrder after = orders.findByPublicToken(order.getPublicToken()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        var inv = inventory.findBySkuId(skuId(sku)).orElseThrow();
        assertThat(inv.getActual()).isEqualTo(9L);
        assertThat(inv.getLocked()).isZero();
    }

    @Test
    @DisplayName("🔴 混合支付：意图三列写入且 coin + cash = amount（库级不变式）；到账后两段各归各位")
    void mixedPaymentWritesSplitColumns() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        topUp(uid, 60_000L);
        String sku = seedSku(10, 350_000L);
        ShopOrder order = placeOrder(uid, sku, 1, 350_000L);
        assertThat(order.getPayChannel()).isEqualTo(PayChannel.MIXED);
        assertThat(order.getCoinAmount()).isEqualTo(60_000L);
        assertThat(order.getCashAmount()).isEqualTo(290_000L);

        var result = payments.pay(uid, order.getPublicToken(), null);
        PaymentIntent intent = paymentIntents.findByToken(result.paymentIntentToken())
                .orElseThrow();
        assertThat(intent.getChannel()).isEqualTo(PayChannel.MIXED);
        assertThat(intent.getCoinAmount()).isEqualTo(60_000L);
        assertThat(intent.getCashAmount()).isEqualTo(290_000L);
        assertThat(intent.getCoinAmount() + intent.getCashAmount()).isEqualTo(intent.getAmount());

        payCallback(result.paymentIntentToken());

        // Coin 段在到账时才扣（下单时只锁库存不扣款）
        assertThat(wallet.balanceOf(uid)).isZero();
        assertThat(orders.findByPublicToken(order.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
    }

    @Test
    @DisplayName("🔴 重复回调不重复扣库存、不重复扣币（回调与轮询同时到达只生效一次）")
    void duplicateCallbackIsIdempotent() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        topUp(uid, 60_000L);
        String sku = seedSku(10, 350_000L);
        ShopOrder order = placeOrder(uid, sku, 1, 350_000L);
        var result = payments.pay(uid, order.getPublicToken(), null);

        payCallback(result.paymentIntentToken());
        // 第二次投递同一笔（真实世界里就是回调重试 + 客户端轮询各来一次）
        payCallback(result.paymentIntentToken());

        var inv = inventory.findBySkuId(skuId(sku)).orElseThrow();
        assertThat(inv.getActual()).as("扣两次就是白送一件货").isEqualTo(9L);
        assertThat(inv.getLocked()).isZero();
        assertThat(wallet.balanceOf(uid)).as("扣两次币就是资损").isZero();
    }

    @Test
    @DisplayName("🔴 fulfillPaid 自身幂等：连调两次仍只扣一次库存（不靠上游意图幂等兜底）")
    void fulfillPaidIsIdempotentOnItsOwn() {
        // ⚠️ 变异验证 B1 暴露的假绿：上面那条「重复回调」用例即使删掉 fulfillPaid 的状态守卫
        //    也照样全绿 —— 因为 applyCallback 的「已终态即返回」把第二次拦在了更外层。
        //    纵深防御会制造假阳性绿灯，所以这里**绕过意图层直接连调两次**，
        //    让断言落在 fulfillPaid 自己的守卫上。
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        String sku = seedSku(10, 285_000L);
        ShopOrder order = placeOrder(uid, sku, 1, 285_000L);

        payments.fulfillPaid(orders.findByPublicToken(order.getPublicToken()).orElseThrow());
        payments.fulfillPaid(orders.findByPublicToken(order.getPublicToken()).orElseThrow());

        var inv = inventory.findBySkuId(skuId(sku)).orElseThrow();
        assertThat(inv.getActual()).as("扣两次就是白送一件货").isEqualTo(9L);
        assertThat(inv.getLocked()).isZero();
    }

    @Test
    @DisplayName("重复点「去支付」取回同一个意图，不向网关重复下单")
    void payTwiceReusesSameIntent() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        String sku = seedSku(10, 285_000L);
        ShopOrder order = placeOrder(uid, sku, 1, 285_000L);

        var first = payments.pay(uid, order.getPublicToken(), null);
        var second = payments.pay(uid, order.getPublicToken(), null);

        assertThat(second.paymentIntentToken()).isEqualTo(first.paymentIntentToken());
    }

    // ---------- 🔴 AD-8 超时：必须把库存还回去 ----------

    @Test
    @DisplayName("🔴 支付窗过期 → 读详情时就地取消并释放库存（懒过期，不等扫描）")
    void lazyExpiryReleasesStock() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        String sku = seedSku(10, 285_000L);
        ShopOrder order = placeOrder(uid, sku, 1, 285_000L);
        assertThat(inventory.findBySkuId(skuId(sku)).orElseThrow().getLocked()).isEqualTo(1L);

        expireOrder(order.getPublicToken());

        ShopOrder read = payments.requireOwn(uid, order.getPublicToken());

        assertThat(read.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        var inv = inventory.findBySkuId(skuId(sku)).orElseThrow();
        assertThat(inv.getLocked()).as("超时必须把库存还给别人").isZero();
        assertThat(inv.getActual()).as("释放不是扣减").isEqualTo(10L);
    }

    @Test
    @DisplayName("🔴 超时扫描兜底：无人查看的订单同样被取消并释放库存")
    void scannerCancelsOverdueOrders() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        String sku = seedSku(10, 285_000L);
        ShopOrder order = placeOrder(uid, sku, 1, 285_000L);
        expireOrder(order.getPublicToken());

        int cancelled = payments.cancelOverdue(50);

        assertThat(cancelled).isPositive();
        assertThat(orders.findByPublicToken(order.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(inventory.findBySkuId(skuId(sku)).orElseThrow().getLocked()).isZero();
    }

    @Test
    @DisplayName("超时后再点支付 → 拿到明确冲突，且不会又开一个二维码")
    void payAfterExpiryIsRejected() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        String sku = seedSku(10, 285_000L);
        ShopOrder order = placeOrder(uid, sku, 1, 285_000L);
        expireOrder(order.getPublicToken());

        assertThatThrownBy(() -> payments.pay(uid, order.getPublicToken(), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("待支付");
        assertThat(orders.findByPublicToken(order.getPublicToken()).orElseThrow()
                .getPaymentIntentToken()).isNull();
    }

    @Test
    @DisplayName("🔴 支付窗是服务端时刻：下单即写 expires_at = 建单 +60min")
    void paymentWindowIsServerSide() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        ShopOrder order = placeOrder(uid, seedSku(10, 100_000L), 1, 100_000L);

        assertThat(order.getExpiresAt()).isNotNull();
        long minutes = ChronoUnit.MINUTES.between(order.getCreatedAt(), order.getExpiresAt());
        assertThat(minutes).isEqualTo(60);
    }

    // ---------- 用户取消 ----------

    @Test
    @DisplayName("用户取消 → 释放库存 + 意图作废（不留一个等不到付款的 PENDING）")
    void userCancelReleasesStockAndFailsIntent() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        String sku = seedSku(10, 285_000L);
        ShopOrder order = placeOrder(uid, sku, 1, 285_000L);
        var result = payments.pay(uid, order.getPublicToken(), null);

        payments.cancel(uid, order.getPublicToken());

        assertThat(orders.findByPublicToken(order.getPublicToken()).orElseThrow().getStatus())
                .isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(inventory.findBySkuId(skuId(sku)).orElseThrow().getLocked()).isZero();
        assertThat(paymentIntents.findByToken(result.paymentIntentToken()).orElseThrow()
                .getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("已支付的订单不能再取消（否则等于白拿货）")
    void paidOrderCannotBeCancelled() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        String sku = seedSku(10, 285_000L);
        ShopOrder order = placeOrder(uid, sku, 1, 285_000L);
        var result = payments.pay(uid, order.getPublicToken(), null);
        payCallback(result.paymentIntentToken());

        assertThatThrownBy(() -> payments.cancel(uid, order.getPublicToken()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不能取消");
    }

    @Test
    @DisplayName("🔒 别人的订单：读、支付、取消一律 404（不泄露 token 是否存在）")
    void otherUsersOrderIsNotFound() {
        long owner = seedUser();
        long stranger = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        ShopOrder order = placeOrder(owner, seedSku(10, 100_000L), 1, 100_000L);

        assertThatThrownBy(() -> payments.requireOwn(stranger, order.getPublicToken()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("订单不存在");
        assertThatThrownBy(() -> payments.cancel(stranger, order.getPublicToken()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("订单不存在");
    }

    /** 把支付窗拨到过去（真等 60 分钟不现实；服务端时刻仍是唯一判定依据）。 */
    private void expireOrder(String orderToken) {
        jdbc.update("UPDATE shop_orders SET expires_at = ? WHERE public_token = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), orderToken);
    }
}

package com.tailtopia.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.service.AdminShopOrderService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.CompletionSource;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.ShopOrderDetailView;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderExpiryScanner;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：Epic 4 全链路联调（Story 4.6）。
 *
 * <p>🔴 <b>本类存在的理由：五条 story 各自绿灯 ≠ 一笔订单真的能走完全程。</b>
 * 它走的是运营与用户各自真实会走的那条路 —— <b>后台发货 → 用户在详情里看到承运商与单号 →
 * 确认收货 → 订单 COMPLETED</b>，全程经真实 service，不用 JDBC 抄近路。
 *
 * <p>🔴 <b>并且逐条验证 SPEC-2 的三条出口</b>：① 后台标记 ② 用户确认 ③ M 日自动。
 * 只验其中一条是没有意义的 —— SPEC-2 说的正是「只有一条出口就会死锁」，
 * 而三条边在代码里共用同一个 {@code markDelivered}，只看最终状态的话，
 * 验一条和验三条看起来一模一样。真正区分它们的是 {@code deliverySource}。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class Epic4ChainIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private CartService carts;
    @Autowired
    private CheckoutService checkout;
    @Autowired
    private ShopOrderPaymentService payments;
    @Autowired
    private ShopOrderFulfillmentService fulfillment;
    @Autowired
    private AdminShopOrderService adminOrders;
    @Autowired
    private ShopOrderExpiryScanner scanner;
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
    private static final long ADMIN = 1L;

    // ---------- 造数 ----------

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "e4" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class,
                "e4" + n);
    }

    private String seedSku(long stock, long price) {
        String pToken = "e4p" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "e4s" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)""", sToken, pid, price);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)",
                sid, stock);
        return sToken;
    }

    /** 走真实链路造一笔【已付款待发货】订单（纯 PawCoin，最短可信路径）。 */
    private Ctx paidOrder() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "e4-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Ke4" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Melawai IV No. 12", "12160", "Rumah"))
                .getPublicToken();
        carts.add(uid, seedSku(10, 100_000L), 1);
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, o.getPublicToken(), null);
        return new Ctx(uid, orders.findByPublicToken(o.getPublicToken()).orElseThrow());
    }

    private record Ctx(long userId, ShopOrder order) {
    }

    private ShopOrder reload(String token) {
        return orders.findByPublicToken(token).orElseThrow();
    }

    private void backdate(String column, String token, int days) {
        jdbc.update("UPDATE shop_orders SET " + column + " = ? WHERE public_token = ?",
                java.sql.Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), token);
    }

    // ---------- 主链路 ----------

    @Test
    @DisplayName("🔴 全程：后台发货 → App 详情拿到单号与跳转地址 → 用户确认收货 → COMPLETED")
    void fullChainShipToCompleted() {
        Ctx c = paidOrder();
        String token = c.order().getPublicToken();
        String tracking = "JP" + SEQ.incrementAndGet();

        // ① 运营发货（走后台 service：领域动作 + 审计 + 推送）
        adminOrders.ship(token, "JNE", tracking, 18_000L, ADMIN);
        assertThat(reload(token).getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);

        // ② 用户在 App 详情里看到承运商 / 单号 / 官网地址（FR-103：只跳转，不渲染轨迹）
        ShopOrder order = payments.requireOwn(c.userId(), token);
        List<Shipment> pkgs = fulfillment.shipmentsOf(order.getId());
        // 本组用例断言的是包裹，与缩略图无关 ⇒ 显式传空图表（见 ShopOrderDetailView.of 的说明）。
        ShopOrderDetailView view =
                ShopOrderDetailView.of(order, payments.linesOf(order), pkgs, java.util.Map.of());
        assertThat(view.packages()).hasSize(1);
        assertThat(view.packages().get(0).carrierName()).isEqualTo("JNE");
        assertThat(view.packages().get(0).trackingNo()).isEqualTo(tracking);
        assertThat(view.packages().get(0).trackingUrl()).startsWith("https://");
        assertThat(view.shippedAt()).isNotNull();

        // ③ 用户确认收货（SPEC-2 出口②：已发货态即可，不必等系统标记送达）
        fulfillment.confirmReceipt(c.userId(), token);

        ShopOrder done = reload(token);
        assertThat(done.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(done.getDeliveredAt()).as("🔴 签收时刻必须被写下").isNotNull();
        assertThat(done.returnWindowEndsAt()).isNotNull();

        // ④ 发货通知已发出，深链目标是不可枚举的订单 token
        Long notified = jdbc.queryForObject("""
                SELECT count(*) FROM notifications
                WHERE recipient_user_id = ? AND type = ? AND target_ref = ?""",
                Long.class, c.userId(), NotificationType.SHOP_ORDER_SHIPPED.name(), token);
        assertThat(notified).isEqualTo(1L);
    }

    // ---------- 🔴 SPEC-2 三条出口，逐条单独验证 ----------

    @Test
    @DisplayName("🔴 SPEC-2 出口①：后台标记 —— 订单脱离 SHIPPED，来源记为 ADMIN_MARK")
    void exitOneAdminMarkLeavesShipped() {
        Ctx c = paidOrder();
        String token = c.order().getPublicToken();
        adminOrders.ship(token, "JNE", "JP" + SEQ.incrementAndGet(), 1L, ADMIN);

        adminOrders.markDelivered(token, ADMIN);

        ShopOrder after = reload(token);
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
        assertThat(after.getDeliverySource()).isEqualTo(DeliverySource.ADMIN_MARK);
    }

    @Test
    @DisplayName("🔴 SPEC-2 出口②：用户确认 —— 订单脱离 SHIPPED，来源记为 USER_CONFIRM")
    void exitTwoUserConfirmLeavesShipped() {
        Ctx c = paidOrder();
        String token = c.order().getPublicToken();
        adminOrders.ship(token, "SICEPAT", "SC" + SEQ.incrementAndGet(), 1L, ADMIN);

        fulfillment.confirmReceipt(c.userId(), token);

        ShopOrder after = reload(token);
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(after.getDeliverySource()).isEqualTo(DeliverySource.USER_CONFIRM);
        assertThat(after.getCompletionSource()).isEqualTo(CompletionSource.USER_CONFIRM);
    }

    @Test
    @DisplayName("🔴 SPEC-2 出口③：M=7 日自动 —— 订单脱离 SHIPPED，来源记为 AUTO_TIMEOUT")
    void exitThreeAutoTimeoutLeavesShipped() {
        Ctx c = paidOrder();
        String token = c.order().getPublicToken();
        adminOrders.ship(token, "ANTERAJA", "AR" + SEQ.incrementAndGet(), 1L, ADMIN);
        backdate("shipped_at", token, 8);

        scanner.scanFulfillment();

        ShopOrder after = reload(token);
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
        assertThat(after.getDeliverySource()).isEqualTo(DeliverySource.AUTO_TIMEOUT);
    }

    @Test
    @DisplayName("🔴 无死锁：三条出口都不触发时，最坏路径也在 D14 走到 COMPLETED")
    void noDeadlockOnTheWorstPath() {
        Ctx c = paidOrder();
        String token = c.order().getPublicToken();
        adminOrders.ship(token, "JNE", "JP" + SEQ.incrementAndGet(), 1L, ADMIN);

        backdate("shipped_at", token, 8);
        scanner.scanFulfillment();
        assertThat(reload(token).getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);

        backdate("delivered_at", token, 8);
        scanner.scanFulfillment();

        ShopOrder done = reload(token);
        assertThat(done.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(done.getStatus().isTerminal()).isTrue();
        // 🔴 系统替用户点了确认收货，不该顺带没收他的退货权
        assertThat(done.getDeliveredAt()).isNotNull();
        assertThat(done.isWithinReturnWindow(done.getDeliveredAt())).isTrue();
    }

    // ---------- S-2 一单多包全链路 ----------

    @Test
    @DisplayName("S-2 全链路：两个包裹分别发出，全部送达后订单才转 DELIVERED")
    void multiPackageChain() {
        Ctx c = paidOrder();
        String token = c.order().getPublicToken();
        Shipment a = adminOrders.ship(token, "JNE", "JP" + SEQ.incrementAndGet(), 10_000L, ADMIN);
        Shipment b = adminOrders.ship(token, "ANTERAJA", "AR" + SEQ.incrementAndGet(), 12_000L,
                ADMIN);

        adminOrders.markPackageDelivered(token, a.getId(), ADMIN);
        assertThat(reload(token).getStatus())
                .as("还有包裹在路上").isEqualTo(ShopOrderStatus.SHIPPED);

        adminOrders.markPackageDelivered(token, b.getId(), ADMIN);
        ShopOrder after = reload(token);
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
        assertThat(after.getDeliverySource()).isEqualTo(DeliverySource.SHIPMENTS_ALL_DELIVERED);

        // App 详情逐条列出两个包裹，各自带官网地址
        ShopOrder order = payments.requireOwn(c.userId(), token);
        ShopOrderDetailView view = ShopOrderDetailView.of(order, payments.linesOf(order),
                fulfillment.shipmentsOf(order.getId()), java.util.Map.of());
        assertThat(view.packages()).hasSize(2);
        assertThat(view.packages()).allSatisfy(
                p -> assertThat(p.trackingUrl()).startsWith("https://"));
    }

    // ---------- 不碰 Epic 3 ----------

    @Test
    @DisplayName("🔴 Epic 4 没动 Epic 3 的边：待支付订单依然不能被发货")
    void epic3EdgesUntouched() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Ke4" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        carts.add(uid, seedSku(10, 285_000L), 1);
        ShopOrder pending = checkout.placeOrder(uid, addr, null, null);

        assertThat(pending.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> adminOrders.ship(pending.getPublicToken(), "JNE",
                        "JP-nope", 1L, ADMIN))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
    }
}

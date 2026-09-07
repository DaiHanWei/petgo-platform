package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.CompletionSource;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShipmentRepository;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：履约段状态机与自动确认收货（Story 4.1，SPEC-2 / SPEC-5 / S-1 / S-2 / FR-102）。
 *
 * <p>🔴 本类的核心断言是 <b>SPEC-2 的三条出口在真实链路上各自可达</b> ——
 * L0 只能证明状态枚举允许这条边，证明不了「后台点了那个按钮之后订单真的动了」。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class ShopOrderFulfillmentIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private CheckoutService checkout;
    @Autowired
    private ShopOrderPaymentService payments;
    @Autowired
    private ShopOrderFulfillmentService fulfillment;
    @Autowired
    private ShopOrderExpiryScanner scanner;
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
    private ShipmentRepository shipments;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    // ---------- 造数（走真实 service，不直接 INSERT 业务表） ----------

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "ful" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class,
                "ful" + n);
    }

    private String seedSku(long stock, long price) {
        String pToken = "fp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "fs" + SEQ.incrementAndGet();
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
        String kec = "Kful" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        return addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
    }

    /** 造一笔【已付款待发货】的订单（纯 PawCoin 当场结清，最短路径）。 */
    private ShopOrder paidOrder(long uid) {
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "ful-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String sku = seedSku(10, 100_000L);
        String addr = seedAddress(uid);
        carts.add(uid, sku, 1);
        ShopOrder order = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, order.getPublicToken(), null);
        ShopOrder paid = orders.findByPublicToken(order.getPublicToken()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        return paid;
    }

    private ShopOrder reload(String token) {
        return orders.findByPublicToken(token).orElseThrow();
    }

    /** 把发货/送达时刻拨到过去（真等 7 天不现实；服务端时刻仍是唯一判定依据）。 */
    private void backdateShipped(String token, int days) {
        jdbc.update("UPDATE shop_orders SET shipped_at = ? WHERE public_token = ?",
                java.sql.Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), token);
    }

    private void backdateDelivered(String token, int days) {
        jdbc.update("UPDATE shop_orders SET delivered_at = ? WHERE public_token = ?",
                java.sql.Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), token);
    }

    // ---------- 发货 ----------

    @Test
    @DisplayName("发货：登记包裹 → 订单转 SHIPPED，包裹带承运商/单号/承运成本")
    void shipCreatesShipmentAndAdvancesOrder() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);

        Shipment s = fulfillment.ship(order.getPublicToken(), Carrier.JNE,
                "JP" + SEQ.incrementAndGet(), 18_000L);

        assertThat(reload(order.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
        assertThat(reload(order.getPublicToken()).getShippedAt()).isNotNull();
        assertThat(s.getCarrier()).isEqualTo(Carrier.JNE);
        assertThat(s.getCarrierCost()).isEqualTo(18_000L);
        assertThat(shipments.countByShopOrderId(order.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 同一承运商的同一单号不得重复录入（重复会让承运成本被计两次）")
    void duplicateTrackingNoRejected() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        String no = "JP" + SEQ.incrementAndGet();
        fulfillment.ship(order.getPublicToken(), Carrier.JNE, no, 18_000L);

        ShopOrder other = paidOrder(seedUser());
        assertThatThrownBy(() -> fulfillment.ship(other.getPublicToken(), Carrier.JNE, no, 1L))
                .isInstanceOf(AppException.class).hasMessageContaining("已录入过");
    }

    @Test
    @DisplayName("未付款订单不可发货")
    void cannotShipUnpaidOrder() {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        zones.setFreeShippingThreshold(0, ACTOR);
        String sku = seedSku(10, 285_000L);
        carts.add(uid, sku, 1);
        ShopOrder pending = checkout.placeOrder(uid, seedAddress(uid), null, null);

        assertThatThrownBy(() -> fulfillment.ship(pending.getPublicToken(), Carrier.JNE, "X1", 0L))
                .isInstanceOf(AppException.class).hasMessageContaining("不可发货");
    }

    // ---------- 🔴 SPEC-2 三条出口，各自单独验证 ----------

    @Test
    @DisplayName("🔴 SPEC-2 出口①：后台标记已送达 → 订单与所有包裹一并转送达")
    void exitOneAdminMarkDelivered() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        fulfillment.ship(order.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 1L);

        fulfillment.markDeliveredByAdmin(order.getPublicToken());

        ShopOrder after = reload(order.getPublicToken());
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
        assertThat(after.getDeliverySource()).isEqualTo(DeliverySource.ADMIN_MARK);
        assertThat(after.getDeliveredAt()).as("🔴 签收时刻 = 退货窗口起点").isNotNull();
        assertThat(shipments.findByShopOrderIdOrderByIdAsc(order.getId()))
                .allMatch(Shipment::isDelivered);
    }

    @Test
    @DisplayName("🔴 SPEC-2 出口②：用户在【已发货】态直接确认收货 → 一步到 COMPLETED 且写下签收时刻")
    void exitTwoUserConfirmFromShipped() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        fulfillment.ship(order.getPublicToken(), Carrier.SICEPAT, "SC" + SEQ.incrementAndGet(), 1L);

        fulfillment.confirmReceipt(uid, order.getPublicToken());

        ShopOrder after = reload(order.getPublicToken());
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(after.getDeliverySource()).isEqualTo(DeliverySource.USER_CONFIRM);
        assertThat(after.getCompletionSource()).isEqualTo(CompletionSource.USER_CONFIRM);
        assertThat(after.getDeliveredAt()).isNotNull();
        assertThat(after.returnWindowEndsAt()).isNotNull();
    }

    @Test
    @DisplayName("🔴 SPEC-2 出口③：发货起 7 日无标记 → 定时扫描自动置送达")
    void exitThreeAutoDeliverByScan() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        fulfillment.ship(order.getPublicToken(), Carrier.ANTERAJA, "AR" + SEQ.incrementAndGet(), 1L);
        backdateShipped(order.getPublicToken(), 8);

        scanner.scanFulfillment();

        ShopOrder after = reload(order.getPublicToken());
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
        assertThat(after.getDeliverySource()).isEqualTo(DeliverySource.AUTO_TIMEOUT);
        assertThat(shipments.findByShopOrderIdOrderByIdAsc(order.getId()))
                .as("包裹状态不得与订单状态脱节").allMatch(Shipment::isDelivered);
    }

    @Test
    @DisplayName("发货未满 7 日的订单不被自动置送达")
    void freshShipmentNotAutoDelivered() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        fulfillment.ship(order.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 1L);
        backdateShipped(order.getPublicToken(), 6);

        scanner.scanFulfillment();

        assertThat(reload(order.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
    }

    // ---------- 自动确认收货 + 退货窗口并存 ----------

    @Test
    @DisplayName("🔴 送达起 7 日未确认 → 自动 COMPLETED，且签收时刻与退货窗口原样保留")
    void autoCompleteKeepsReturnWindow() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        fulfillment.ship(order.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 1L);
        fulfillment.markDeliveredByAdmin(order.getPublicToken());
        Instant signedAt = reload(order.getPublicToken()).getDeliveredAt();

        // 送达 7 天前 → 自动完成到期；此时退货窗口（签收 +7 日）刚好走到边界
        backdateDelivered(order.getPublicToken(), 8);
        scanner.scanFulfillment();

        ShopOrder after = reload(order.getPublicToken());
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(after.getCompletionSource()).isEqualTo(CompletionSource.AUTO_TIMEOUT);
        assertThat(after.getDeliveredAt()).as("🔴 自动确认不得清空签收时刻").isNotNull();
        assertThat(after.returnWindowEndsAt())
                .isEqualTo(after.getDeliveredAt().plus(ShopOrder.RETURN_WINDOW));
        // 签收当天仍在窗内 —— 「已完成」不等于「不能再退」
        assertThat(after.isWithinReturnWindow(after.getDeliveredAt())).isTrue();
        assertThat(signedAt).isNotNull();
    }

    @Test
    @DisplayName("最坏路径：D8 自动送达 → 再 D8 自动完成，全程无人工介入也不卡死")
    void worstCasePathReachesCompletedWithoutHumanTouch() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        fulfillment.ship(order.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 1L);

        backdateShipped(order.getPublicToken(), 8);
        scanner.scanFulfillment();
        assertThat(reload(order.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);

        backdateDelivered(order.getPublicToken(), 8);
        scanner.scanFulfillment();
        ShopOrder done = reload(order.getPublicToken());
        assertThat(done.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(done.getStatus().isTerminal()).isTrue();
    }

    // ---------- S-2 一单多包 ----------

    @Test
    @DisplayName("🔴 S-2：两个包裹只送达一个时订单仍是 SHIPPED；全送达才转 DELIVERED")
    void orderDeliveredOnlyWhenAllPackagesDelivered() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        Shipment a = fulfillment.ship(order.getPublicToken(), Carrier.JNE,
                "JP" + SEQ.incrementAndGet(), 10_000L);
        Shipment b = fulfillment.ship(order.getPublicToken(), Carrier.SICEPAT,
                "SC" + SEQ.incrementAndGet(), 12_000L);
        assertThat(shipments.countByShopOrderId(order.getId())).isEqualTo(2);

        boolean firstClosed = fulfillment.markShipmentDelivered(order.getPublicToken(), a.getId());
        assertThat(firstClosed).isFalse();
        assertThat(reload(order.getPublicToken()).getStatus())
                .as("🔴 还有包裹在路上，订单不得转送达").isEqualTo(ShopOrderStatus.SHIPPED);

        boolean allClosed = fulfillment.markShipmentDelivered(order.getPublicToken(), b.getId());
        assertThat(allClosed).isTrue();
        ShopOrder after = reload(order.getPublicToken());
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
        assertThat(after.getDeliverySource()).isEqualTo(DeliverySource.SHIPMENTS_ALL_DELIVERED);
    }

    @Test
    @DisplayName("🔴 S-2：签收时刻取【最后一个】包裹的送达时刻（7 日自动确认据此起算）")
    void signedAtIsLastPackageDelivery() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        Shipment a = fulfillment.ship(order.getPublicToken(), Carrier.JNE,
                "JP" + SEQ.incrementAndGet(), 1L);
        Shipment b = fulfillment.ship(order.getPublicToken(), Carrier.ANTERAJA,
                "AR" + SEQ.incrementAndGet(), 1L);

        fulfillment.markShipmentDelivered(order.getPublicToken(), a.getId());
        // 把第一个包裹的送达时刻拨到很早，模拟先到的那一件
        jdbc.update("UPDATE shipments SET delivered_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS)), a.getId());
        fulfillment.markShipmentDelivered(order.getPublicToken(), b.getId());

        ShopOrder after = reload(order.getPublicToken());
        Instant lastPackage = shipments.findById(b.getId()).orElseThrow().getDeliveredAt();
        assertThat(after.getDeliveredAt()).isCloseTo(lastPackage,
                org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
        assertThat(after.getDeliveredAt())
                .as("🔴 取先到那件的时刻会让退货窗口凭空缩短")
                .isAfter(Instant.now().minus(2, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("一单多包：第二次发货不改写订单发货时刻（否则兜底被无限推后）")
    void secondShipmentKeepsOriginalShippedAt() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        fulfillment.ship(order.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 1L);
        Instant first = reload(order.getPublicToken()).getShippedAt();
        fulfillment.ship(order.getPublicToken(), Carrier.SICEPAT, "SC" + SEQ.incrementAndGet(), 1L);

        assertThat(reload(order.getPublicToken()).getShippedAt()).isEqualTo(first);
    }

    // ---------- 越权与幂等 ----------

    @Test
    @DisplayName("🔒 非订单主人确认收货 → 404（与 Epic 3 同口径，不泄漏 token 存在）")
    void strangerCannotConfirmReceipt() {
        long uid = seedUser();
        long stranger = seedUser();
        ShopOrder order = paidOrder(uid);
        fulfillment.ship(order.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 1L);

        assertThatThrownBy(() -> fulfillment.confirmReceipt(stranger, order.getPublicToken()))
                .isInstanceOf(AppException.class).hasMessageContaining("订单不存在");
        assertThat(reload(order.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("待发货态不可确认收货；重复确认幂等不报错")
    void confirmReceiptGuards() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        assertThatThrownBy(() -> fulfillment.confirmReceipt(uid, order.getPublicToken()))
                .isInstanceOf(AppException.class).hasMessageContaining("不可确认收货");

        fulfillment.ship(order.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 1L);
        fulfillment.confirmReceipt(uid, order.getPublicToken());
        fulfillment.confirmReceipt(uid, order.getPublicToken());   // 幂等
        assertThat(reload(order.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("已完成订单不再被自动扫描重复推进")
    void completedOrderIgnoredByScanner() {
        long uid = seedUser();
        ShopOrder order = paidOrder(uid);
        fulfillment.ship(order.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 1L);
        fulfillment.confirmReceipt(uid, order.getPublicToken());
        backdateShipped(order.getPublicToken(), 30);
        backdateDelivered(order.getPublicToken(), 30);

        scanner.scanFulfillment();

        ShopOrder after = reload(order.getPublicToken());
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(after.getCompletionSource()).isEqualTo(CompletionSource.USER_CONFIRM);
    }
}

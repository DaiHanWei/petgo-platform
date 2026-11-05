package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.pay.ChargeQueryResult;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PayException;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.ShopOrderDetailView;
import com.tailtopia.shop.order.notify.LarkMessageClient;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.order.service.ShopPaymentReconcileJob;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * L1：v1.4.2 shop Story 3.3 —— 付款窗口、主动查单与关单后到账（{@code petgo.pay.mode=stub}）。
 *
 * <p>时间推进一律用 {@code jdbc.update} 把 {@code created_at / expires_at / provider_expires_at} 拨回过去
 * （同 {@code ShopOrderPaymentIntegrationTest.expireOrder}）。网关是 stub 的 spy：默认查单回「已过期未付」，
 * 需要 PAID / UNKNOWN 的用例逐条 {@code doReturn}。飞书客户端 mock 计数。
 */
@TestPropertySource(properties = {
        "petgo.shop.sku-cap=500",
        "petgo.shop.order-notify.mode=live",
        "petgo.shop.order-notify.receive-id=oc_integration_test_fake",
        "petgo.shop.order-notify.cron=-"})
class ShopPaymentWindowIntegrationTest extends ApiIntegrationTest {

    @Autowired private CheckoutService checkout;
    @Autowired private ShopOrderPaymentService payments;
    @Autowired private PaymentIntentService paymentIntents;
    @Autowired private ShopPaymentReconcileJob reconcileJob;
    @Autowired private CartService carts;
    @Autowired private ShippingAddressService addresses;
    @Autowired private AdminShippingZoneService zones;
    @Autowired private ShopOrderRepository orders;
    @Autowired private SkuInventoryRepository inventory;
    @Autowired private JdbcTemplate jdbc;

    @MockitoSpyBean private PaymentGateway gateway;
    @MockitoBean private LarkMessageClient lark;

    private static final long ACTOR = 1L;

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "pw" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "pw" + n);
    }

    private String seedSku(long stock, long price) {
        String pToken = "wp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "ws" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, package_weight_g, package_length_cm, package_width_cm, package_height_cm)
                VALUES (?, ?, '3 kg', ?, 1000, 20, 15, 10)""", sToken, pid, price);
        Long sid = jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)", sid, stock);
        return sToken;
    }

    private long skuId(String token) {
        return jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class, token);
    }

    private ShopOrder placeOrder(long uid, String sku) {
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Kpw" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        carts.add(uid, sku, 1);
        return checkout.placeOrder(uid, addr, null, null);
    }

    /** 整单（订单 + 其全部意图）往过去拨 {@code minutes} 分钟。 */
    private void rewind(ShopOrder o, int minutes) {
        jdbc.update("""
                UPDATE shop_orders SET created_at = created_at - make_interval(mins => ?),
                       expires_at = expires_at - make_interval(mins => ?) WHERE id = ?""", minutes, minutes, o.getId());
        jdbc.update("""
                UPDATE payment_intents SET created_at = created_at - make_interval(mins => ?),
                       expires_at = expires_at - make_interval(mins => ?),
                       provider_expires_at = provider_expires_at - make_interval(mins => ?)
                 WHERE shop_order_id = ?""", minutes, minutes, minutes, o.getId());
    }

    private ShopOrder reload(ShopOrder o) {
        return orders.findById(o.getId()).orElseThrow();
    }

    private ShopOrderDetailView detailOf(long uid, ShopOrder o) {
        ShopOrder order = payments.requireOwn(uid, o.getPublicToken());
        return ShopOrderDetailView.of(order, payments.linesOf(order), java.util.List.of(), Map.of(),
                payments.intentOf(order).orElse(null));
    }

    private PaymentIntent intentOf(ShopOrder o) {
        return paymentIntents.findByToken(reload(o).getPaymentIntentToken()).orElseThrow();
    }

    @Test
    @DisplayName("出码后：意图 expires_at = provider_expires_at ≈ 出码 + 65 分钟、shop_order_id 已写；订单 expires_at = 建单 + 60 分钟")
    void issueCodeUsesProviderTtl() {
        long uid = seedUser();
        ShopOrder o = placeOrder(uid, seedSku(10, 100_000L));
        payments.pay(uid, o.getPublicToken(), null);

        PaymentIntent pi = intentOf(o);
        assertThat(pi.getProviderExpiresAt()).isEqualTo(pi.getExpiresAt());
        assertThat(ChronoUnit.MINUTES.between(Instant.now(), pi.getProviderExpiresAt())).isBetween(64L, 65L);
        assertThat(pi.getShopOrderId()).isEqualTo(o.getId());
        assertThat(ChronoUnit.MINUTES.between(reload(o).getCreatedAt(), reload(o).getExpiresAt())).isEqualTo(60);
    }

    @Test
    @DisplayName("🔴 订单窗过、码未失效：不关单 / 显示 CANCELLED / /pay 409 / 全局扫描与轮询都不置 EXPIRED；宽限期到账 → 待发货")
    void gracePeriod() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        ShopOrder o = placeOrder(uid, sku);
        payments.pay(uid, o.getPublicToken(), null);
        rewind(o, 62);   // 订单 62 分钟、码 = 出码 + 65 → 还剩约 3 分钟

        assertThat(payments.closeOverdue(50)).isZero();
        ShopOrderDetailView v = detailOf(uid, o);
        assertThat(v.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(v.displayStatus()).isEqualTo("CANCELLED");
        assertThat(v.canPay()).isFalse();
        assertThatThrownBy(() -> payments.pay(uid, o.getPublicToken(), null))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorTypes.SHOP_ORDER_NOT_PAYABLE));
        paymentIntents.expireOverduePending(200);
        PaymentIntent pi = intentOf(o);
        assertThat(paymentIntents.statusOf(uid, pi.getPublicToken())).isEqualTo(PaymentStatus.PENDING);
        assertThat(intentOf(o).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(inventory.findBySkuId(skuId(sku)).orElseThrow().getLocked()).isEqualTo(1L);

        paymentIntents.applyCallback(new PaymentCallback(pi.getPublicToken(), pi.getGatewayRef(),
                GatewayStatus.PAID, Map.of()));
        assertThat(reload(o).getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        assertThat(detailOf(uid, o).displayStatus()).isEqualTo("PENDING_SHIPMENT");
    }

    @Test
    @DisplayName("🔴 两者都过 + stub 查单 EXPIRED → 关单：库存释放、cancel_reason=PAYMENT_TIMEOUT、意图终态")
    void bothExpiredCloses() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        ShopOrder o = placeOrder(uid, sku);
        payments.pay(uid, o.getPublicToken(), null);
        rewind(o, 70);

        assertThat(payments.closeOverdue(50)).isPositive();

        assertThat(reload(o).getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM shop_orders WHERE id = ?", String.class, o.getId()))
                .isEqualTo("PAYMENT_TIMEOUT");
        assertThat(inventory.findBySkuId(skuId(sku)).orElseThrow().getLocked()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payment_intents WHERE shop_order_id = ? AND status = 'PENDING'""",
                Integer.class, o.getId())).isZero();
        assertThat(detailOf(uid, o).paymentFailureCategory()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("🔴 S-1：查单 UNKNOWN 连跑 10 轮 → 订单仍 PENDING_PAYMENT，第 10 轮发一次飞书")
    void unknownTenRoundsAlertsOnce() {
        long uid = seedUser();
        ShopOrder o = placeOrder(uid, seedSku(10, 100_000L));
        payments.pay(uid, o.getPublicToken(), null);
        rewind(o, 70);
        doReturn(ChargeQueryResult.unknown()).when(gateway).queryCharge(anyString());

        for (int i = 0; i < 10; i++) {
            payments.closeOverdue(50);
        }

        assertThat(reload(o).getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        verify(lark, times(1)).sendText(org.mockito.ArgumentMatchers.contains(reload(o).getDisplayNo()));
    }

    @Test
    @DisplayName("I3：建单 11 分钟后首次出码 → 409；5 分钟出码失败、7 分钟重试成功")
    void qrIssueWindow() {
        long uid = seedUser();
        ShopOrder late = placeOrder(uid, seedSku(10, 100_000L));
        rewind(late, 11);
        assertThatThrownBy(() -> payments.pay(uid, late.getPublicToken(), null))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorTypes.SHOP_ORDER_NOT_PAYABLE));

        ShopOrder o = placeOrder(uid, seedSku(10, 100_000L));
        rewind(o, 5);
        doThrow(new PayException("down")).when(gateway).createCharge(any());
        assertThatThrownBy(() -> payments.pay(uid, o.getPublicToken(), null)).isInstanceOf(PayException.class);
        org.mockito.Mockito.reset(gateway);
        rewind(o, 2);
        var r = payments.pay(uid, o.getPublicToken(), null);
        assertThat(r.payload()).isNotBlank();
    }

    @Test
    @DisplayName("🔴 I1：关单后回调 PAID（去重键已被先前 FAILED 回调占用）→ 意图 PAID + paid_after_close、订单仍 CANCELLED、late_paid_* 落原单")
    void latePaymentAfterClose() {
        long uid = seedUser();
        ShopOrder o = placeOrder(uid, seedSku(10, 100_000L));
        payments.pay(uid, o.getPublicToken(), null);
        rewind(o, 70);
        payments.closeOverdue(50);
        PaymentIntent pi = intentOf(o);
        assertThat(pi.getStatus().isTerminal()).isTrue();

        paymentIntents.applyCallback(new PaymentCallback(pi.getPublicToken(), pi.getGatewayRef(),
                GatewayStatus.FAILED, Map.of()));
        paymentIntents.applyCallback(new PaymentCallback(pi.getPublicToken(), pi.getGatewayRef(),
                GatewayStatus.PAID, Map.of("status", "Expired => Success")));

        PaymentIntent after = intentOf(o);
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(after.isPaidAfterClose()).isTrue();
        ShopOrder r = reload(o);
        assertThat(r.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(r.getLatePaidAmount()).isEqualTo(r.getCashAmount() == null ? r.getTotalAmount() : r.getCashAmount());
        assertThat(r.getLatePaidIntentId()).isEqualTo(after.getId());
    }

    @Test
    @DisplayName("定时查单：PENDING + 查单 PAID → 待发货；关单 23 小时的 FAILED + PAID → 迟到款；25 小时的不再查")
    void reconcileJob() {
        long uid = seedUser();
        ShopOrder live = placeOrder(uid, seedSku(10, 100_000L));
        payments.pay(uid, live.getPublicToken(), null);
        rewind(live, 5);
        PaymentIntent livePi = intentOf(live);

        ShopOrder closed = placeOrder(uid, seedSku(10, 100_000L));
        payments.pay(uid, closed.getPublicToken(), null);
        rewind(closed, 70);
        payments.closeOverdue(50);
        PaymentIntent closedPi = intentOf(closed);
        jdbc.update("UPDATE payment_intents SET updated_at = now() - interval '23 hours' WHERE id = ?", closedPi.getId());

        ShopOrder old = placeOrder(uid, seedSku(10, 100_000L));
        payments.pay(uid, old.getPublicToken(), null);
        rewind(old, 70);
        payments.closeOverdue(50);
        PaymentIntent oldPi = intentOf(old);
        jdbc.update("UPDATE payment_intents SET updated_at = now() - interval '25 hours' WHERE id = ?", oldPi.getId());

        doAnswer(inv -> {
            String ref = inv.getArgument(0);
            return ChargeQueryResult.paid(new PaymentCallback(null, ref, GatewayStatus.PAID, Map.of()));
        }).when(gateway).queryCharge(anyString());

        reconcileJob.run();

        assertThat(reload(live).getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        assertThat(reload(closed).getLatePaidIntentId()).isEqualTo(closedPi.getId());
        assertThat(reload(old).getLatePaidIntentId()).isNull();
        verify(gateway, times(0)).queryCharge(oldPi.getGatewayRef());
        assertThat(livePi.getId()).isNotNull();
    }

    @Test
    @DisplayName("「我已付款」：查单 PAID → paid=true；10 秒内第二次不调网关")
    void paymentCheck() {
        long uid = seedUser();
        ShopOrder o = placeOrder(uid, seedSku(10, 100_000L));
        payments.pay(uid, o.getPublicToken(), null);
        PaymentIntent pi = intentOf(o);
        doReturn(ChargeQueryResult.notPaid(new PaymentCallback(null, pi.getGatewayRef(), GatewayStatus.PENDING,
                Map.of()))).when(gateway).queryCharge(pi.getGatewayRef());

        assertThat(payments.paymentCheck(uid, o.getPublicToken()).paid()).isFalse();
        assertThat(payments.paymentCheck(uid, o.getPublicToken()).paid()).isFalse();
        verify(gateway, times(1)).queryCharge(pi.getGatewayRef());

        ShopOrder o2 = placeOrder(uid, seedSku(10, 100_000L));
        payments.pay(uid, o2.getPublicToken(), null);
        PaymentIntent pi2 = intentOf(o2);
        doReturn(ChargeQueryResult.paid(new PaymentCallback(null, pi2.getGatewayRef(), GatewayStatus.PAID, Map.of())))
                .when(gateway).queryCharge(pi2.getGatewayRef());
        assertThat(payments.paymentCheck(uid, o2.getPublicToken()).paid()).isTrue();
    }
}

package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.CheckoutUnavailableException;
import com.tailtopia.shop.order.dto.ShopOrderDetailView;
import com.tailtopia.shop.order.notify.LarkMessageClient;
import com.tailtopia.shop.order.refund.ShopOrderRefund;
import com.tailtopia.shop.order.refund.ShopRefundStatus;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.LatePaymentService;
import com.tailtopia.shop.order.service.ReorderOutcome;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1：v1.4.2 shop Story 4.2 —— 迟到款重新下单与退款（{@code petgo.pay.mode=stub}，云端不跑，待本地验收）。
 *
 * <p>造数：下单 → 出码 → 拨时间 70 分钟（stub 查单回已过期）→ 关单 → 对原意图回调 PAID → 3.3 把迟到款记到原单。
 * 多退 / 少补用 {@code jdbc} 直接改 {@code late_paid_amount}。
 */
@TestPropertySource(properties = {
        "petgo.shop.sku-cap=500",
        "petgo.shop.order-notify.mode=live",
        "petgo.shop.order-notify.receive-id=oc_integration_test_fake",
        "petgo.shop.order-notify.cron=-"})
class LatePaymentIntegrationTest extends ApiIntegrationTest {

    @Autowired private CheckoutService checkout;
    @Autowired private ShopOrderPaymentService payments;
    @Autowired private PaymentIntentService paymentIntents;
    @Autowired private LatePaymentService latePayments;
    @Autowired private CartService carts;
    @Autowired private ShippingAddressService addresses;
    @Autowired private AdminShippingZoneService zones;
    @Autowired private ShopOrderRepository orders;
    @Autowired private SkuInventoryRepository inventory;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private LarkMessageClient lark;

    private static final long ACTOR = 1L;

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "lp" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "lp" + n);
    }

    private String seedSku(long stock, long price) {
        String pToken = "lpp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "lps" + SEQ.incrementAndGet();
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
        String kec = "Klp" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        carts.add(uid, sku, 1);
        return checkout.placeOrder(uid, addr, null, null);
    }

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

    /** 下单 → 出码 → 关单 → 原意图到账：返回带迟到款的已取消原单。 */
    private ShopOrder lateOrder(long uid, String sku) {
        ShopOrder o = placeOrder(uid, sku);
        payments.pay(uid, o.getPublicToken(), null);
        rewind(o, 70);
        payments.closeOverdue(50);
        PaymentIntent pi = paymentIntents.findByToken(reload(o).getPaymentIntentToken()).orElseThrow();
        paymentIntents.applyCallback(new PaymentCallback(pi.getPublicToken(), pi.getGatewayRef(),
                GatewayStatus.PAID, Map.of()));
        ShopOrder r = reload(o);
        assertThat(r.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(r.getLatePaidAmount()).isNotNull();
        return r;
    }

    private long actualOf(String sku) {
        return inventory.findBySkuId(skuId(sku)).orElseThrow().getActual();
    }

    private ShopOrderDetailView detailOf(long uid, ShopOrder o) {
        ShopOrder order = payments.requireOwn(uid, o.getPublicToken());
        return ShopOrderDetailView.of(order, payments.linesOf(order), List.of(), Map.of(),
                payments.intentOf(order).orElse(null), java.time.Instant.now(), List.of(),
                latePayments.linksOf(order));
    }

    @Test
    @DisplayName("重下单（迟到款 = 现金段）：新单待发货、paid_at、库存扣减、原单 REORDERED、通知一条、详情双向关联；再调 409")
    void reorderHappyPath() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        ShopOrder o = lateOrder(uid, sku);
        long actualBefore = actualOf(sku);

        ReorderOutcome out = latePayments.reorderFromLatePayment(o.getPublicToken(), ACTOR);

        ShopOrder n = orders.findByPublicToken(out.newOrderToken()).orElseThrow();
        assertThat(n.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        assertThat(n.getPaidAt()).isNotNull();
        assertThat(n.getPaymentIntentToken()).isNull();
        assertThat(n.getReorderOfOrderId()).isEqualTo(o.getId());
        assertThat(n.getCashAmount()).isEqualTo(o.getLatePaidAmount());
        assertThat(actualOf(sku)).isEqualTo(actualBefore - 1);
        assertThat(reload(o).getLatePaymentResolution()).isEqualTo("REORDERED");
        assertThat(reload(o).getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = 'SHOP_ORDER_REORDERED'
                   AND target_ref = ?""", Integer.class, uid, out.newOrderToken())).isEqualTo(1);

        ShopOrderDetailView ov = detailOf(uid, o);
        assertThat(ov.reorderedToOrderToken()).isEqualTo(out.newOrderToken());
        assertThat(ov.latePayment().resolution()).isEqualTo("REORDERED");
        ShopOrderDetailView nv = detailOf(uid, n);
        assertThat(nv.reorderedFromOrderNo()).isEqualTo(o.getDisplayNo());
        assertThat(nv.reorderedFromOrderToken()).isEqualTo(o.getPublicToken());

        assertThatThrownBy(() -> latePayments.reorderFromLatePayment(o.getPublicToken(), ACTOR))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorTypes.LATE_PAYMENT_ALREADY_RESOLVED));
        assertThatThrownBy(() -> latePayments.refundLatePayment(o.getPublicToken(), ACTOR))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorTypes.LATE_PAYMENT_ALREADY_RESOLVED));
    }

    @Test
    @DisplayName("多退：迟到款多 20,425 → 用户余额 +20,425（REFUND / SHOP_LATE_EXCESS），无 BONUS")
    void excessRefundedAsPawcoin() {
        long uid = seedUser();
        ShopOrder o = lateOrder(uid, seedSku(10, 100_000L));
        jdbc.update("UPDATE shop_orders SET late_paid_amount = late_paid_amount + 20425 WHERE id = ?", o.getId());

        ReorderOutcome out = latePayments.reorderFromLatePayment(o.getPublicToken(), ACTOR);

        assertThat(out.excessToPawcoin()).isEqualTo(20_425L);
        assertThat(jdbc.queryForObject("""
                SELECT coalesce(sum(delta), 0) FROM pawcoin_transactions
                 WHERE user_id = ? AND type = 'REFUND' AND ref_type = 'SHOP_LATE_EXCESS'""", Long.class, uid))
                .isEqualTo(20_425L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pawcoin_transactions WHERE user_id = ? AND type = 'BONUS'",
                Integer.class, uid)).isZero();
    }

    @Test
    @DisplayName("少补且余额不足 → 409 reorder-coin-insufficient，整体回滚：resolution 仍 NULL、无新单、库存未动")
    void shortfallWithoutCoinRollsBack() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        ShopOrder o = lateOrder(uid, sku);
        jdbc.update("UPDATE shop_orders SET late_paid_amount = late_paid_amount - 29575 WHERE id = ?", o.getId());
        long actualBefore = actualOf(sku);

        assertThatThrownBy(() -> latePayments.reorderFromLatePayment(o.getPublicToken(), ACTOR))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorTypes.REORDER_COIN_INSUFFICIENT));
        assertThat(reload(o).getLatePaymentResolution()).isNull();
        assertThat(orders.findByReorderOfOrderId(o.getId())).isEmpty();
        assertThat(actualOf(sku)).isEqualTo(actualBefore);
        assertThat(inventory.findBySkuId(skuId(sku)).orElseThrow().getLocked()).isZero();
    }

    @Test
    @DisplayName("库存不足 → 409 unavailable-lines，处理权释放（可改走退款）")
    void outOfStockThenRefund() {
        long uid = seedUser();
        String sku = seedSku(1, 100_000L);
        ShopOrder o = lateOrder(uid, sku);
        jdbc.update("UPDATE sku_inventory SET actual = 0 WHERE sku_id = ?", skuId(sku));

        assertThatThrownBy(() -> latePayments.reorderFromLatePayment(o.getPublicToken(), ACTOR))
                .isInstanceOf(CheckoutUnavailableException.class);
        assertThat(reload(o).getLatePaymentResolution()).isNull();

        ShopOrderRefund r = latePayments.refundLatePayment(o.getPublicToken(), ACTOR);
        assertThat(r.getCashRefund()).isEqualTo(o.getLatePaidAmount());
        assertThat(r.getCoinRefund()).isZero();
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.AWAITING_DESTINATION);
        ShopOrder after = reload(o);
        assertThat(after.getLatePaymentResolution()).isEqualTo("REFUNDED");
        assertThat(after.getRefundedTotal()).isZero();
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = 'SHOP_ORDER_REORDERED'""",
                Integer.class, uid)).isZero();
    }

    @Test
    @DisplayName("并发：同一原单重下单与退款同时提交 → 恰好一个成功")
    void concurrentReorderAndRefund() throws Exception {
        long uid = seedUser();
        ShopOrder o = lateOrder(uid, seedSku(10, 100_000L));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> {
                start.await();
                try {
                    latePayments.reorderFromLatePayment(o.getPublicToken(), ACTOR);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            });
            Future<Boolean> b = pool.submit(() -> {
                start.await();
                try {
                    latePayments.refundLatePayment(o.getPublicToken(), ACTOR);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            });
            start.countDown();
            int ok = (a.get(30, TimeUnit.SECONDS) ? 1 : 0) + (b.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reload(o).getLatePaymentResolution()).isIn("REORDERED", "REFUNDED");
    }
}

package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 3.3 AC6.2 —— 到账按订单状态分支：宽限期转待发货、关单后记迟到款、重复付款告警。 */
class ShopOrderPaidHandlerLateTest {

    private final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    private final ShopOrderPaymentService payments = mock(ShopOrderPaymentService.class);
    private final CheckoutService checkout = mock(CheckoutService.class);
    private final PaymentIntentService paymentIntents = mock(PaymentIntentService.class);
    private final ShopPaymentAlerts alerts = mock(ShopPaymentAlerts.class);
    private final ShopOrderPaidHandler handler = new ShopOrderPaidHandler(orders, payments, checkout,
            paymentIntents, alerts);

    /** 绑定到订单 {@code orderId} 的已到账意图（MIXED：总额 220,000 = 币 30,000 + 现金 190,000）。 */
    private PaymentIntent mixedIntent(long intentId, Long orderId) {
        PaymentIntent pi = PaymentIntent.createMixed(9L, PaymentPurpose.SHOP_ORDER, 220_000L, 30_000L, 190_000L,
                java.math.BigDecimal.ZERO, "IDR", "pi-" + intentId, null);
        ReflectionTestUtils.setField(pi, "id", intentId);
        if (orderId != null) {
            pi.bindShopOrder(orderId);
        }
        when(paymentIntents.findById(intentId)).thenReturn(Optional.of(pi));
        return pi;
    }

    private PaymentIntentPaidEvent paid(PaymentIntent pi) {
        return new PaymentIntentPaidEvent(pi.getId(), pi.getPublicToken(), 9L, PaymentPurpose.SHOP_ORDER,
                pi.getChannel(), pi.getAmount(), "IDR");
    }

    private ShopOrder order(long id) {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(id, 9L, 30_000L, CheckoutSource.CART);
        when(orders.findById(id)).thenReturn(Optional.of(o));
        return o;
    }

    @Test
    @DisplayName("🔴 S-7：订单仍 PENDING_PAYMENT（宽限期，订单窗已过）→ markPaid(GATEWAY) 转待发货；按 shop_order_id 找单")
    void gracePeriodPaymentMarksPaid() {
        ShopOrder o = order(1L);
        ReflectionTestUtils.setField(o, "expiresAt", Instant.now().minus(Duration.ofMinutes(2)));
        PaymentIntent pi = mixedIntent(100L, 1L);
        when(payments.markPaid(o, PaidSource.GATEWAY)).thenReturn(true);

        handler.onPaid(paid(pi));

        verify(payments).markPaid(o, PaidSource.GATEWAY);
        verify(orders, never()).findByPaymentIntentToken(anyString());
        verify(orders, never()).recordLatePayment(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("🔴 I1：订单已 CANCELLED → recordLatePayment(amount = 现金段 190,000)，不改状态、不 markPaid")
    void cancelledRecordsLatePaymentWithCashSegment() {
        ShopOrder o = order(2L);
        o.markCancelled(CancelReason.PAYMENT_TIMEOUT, Instant.now());
        PaymentIntent pi = mixedIntent(200L, 2L);
        when(orders.recordLatePayment(eq(2L), anyLong(), any(), eq(200L))).thenReturn(1);

        handler.onPaid(paid(pi));

        verify(orders).recordLatePayment(eq(2L), eq(190_000L), any(), eq(200L));
        verify(payments, never()).markPaid(any(), any());
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        verify(alerts, never()).alert(anyString());
    }

    @Test
    @DisplayName("QRIS 意图迟到款金额 = 意图总额；存量意图（无 shop_order_id）回退 payment_intent_token 找单")
    void qrisAmountAndLegacyLookup() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(3L, 9L, 0L, CheckoutSource.CART);
        o.markCancelled(CancelReason.PAYMENT_TIMEOUT, Instant.now());
        PaymentIntent pi = PaymentIntent.create(9L, PaymentPurpose.SHOP_ORDER, PayChannel.QRIS, 220_000L, "IDR",
                "pi-300");
        ReflectionTestUtils.setField(pi, "id", 300L);
        when(paymentIntents.findById(300L)).thenReturn(Optional.of(pi));
        when(orders.findByPaymentIntentToken("pi-300")).thenReturn(Optional.of(o));
        when(orders.recordLatePayment(anyLong(), anyLong(), any(), anyLong())).thenReturn(1);

        handler.onPaid(paid(pi));

        verify(orders).recordLatePayment(eq(3L), eq(220_000L), any(), eq(300L));
    }

    @Test
    @DisplayName("已有不同的迟到意图 → 不覆盖（条件更新 0 行）+ ERROR + 飞书")
    void secondLatePaymentAlerts() {
        ShopOrder o = order(4L);
        o.markCancelled(CancelReason.PAYMENT_TIMEOUT, Instant.now());
        ReflectionTestUtils.setField(o, "latePaidIntentId", 999L);
        PaymentIntent pi = mixedIntent(400L, 4L);
        when(orders.recordLatePayment(anyLong(), anyLong(), any(), anyLong())).thenReturn(0);

        handler.onPaid(paid(pi));

        verify(alerts).alert(contains("同一订单第二笔迟到款"));
    }

    @Test
    @DisplayName("已付款订单再到账（重复付款）→ 照记录 + ERROR + 飞书")
    void duplicatePaymentOnPaidOrder() {
        ShopOrder o = order(5L);
        o.markPaid(Instant.now());
        PaymentIntent pi = mixedIntent(500L, 5L);
        when(orders.recordLatePayment(anyLong(), anyLong(), any(), anyLong())).thenReturn(1);

        handler.onPaid(paid(pi));

        verify(orders).recordLatePayment(eq(5L), eq(190_000L), any(), eq(500L));
        verify(alerts).alert(contains("重复付款"));
        verify(payments, never()).markPaid(any(), any());
    }

    @Test
    @DisplayName("🔴 拍板③：找不到订单 → ERROR + 飞书，且不抛（抛会回滚意图 PAID）")
    void missingOrderAlertsAndDoesNotThrow() {
        PaymentIntent pi = mixedIntent(600L, null);
        when(orders.findByPaymentIntentToken("pi-600")).thenReturn(Optional.empty());

        assertThatCode(() -> handler.onPaid(paid(pi))).doesNotThrowAnyException();

        verify(alerts).alert("有一笔电商到账找不到订单：intentId=600");
    }

    @Test
    @DisplayName("迟到款分支意外异常 → catch 后 ERROR，不向上抛")
    void lateBranchSwallowsErrors() {
        ShopOrder o = order(7L);
        o.markCancelled(CancelReason.PAYMENT_TIMEOUT, Instant.now());
        PaymentIntent pi = mixedIntent(700L, 7L);
        when(orders.recordLatePayment(anyLong(), anyLong(), any(), anyLong()))
                .thenThrow(new IllegalStateException("db"));

        assertThatCode(() -> handler.onPaid(paid(pi))).doesNotThrowAnyException();
    }
}

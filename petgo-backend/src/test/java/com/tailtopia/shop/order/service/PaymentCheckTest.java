package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.dto.PaymentCheckView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 3.3 AC9 —— 「我已付款」：10 秒节流、非待付款不查、查单失败 paid=false。 */
class PaymentCheckTest {

    private static final long UID = 9L;

    private final ShopPaymentTestRig rig = new ShopPaymentTestRig();
    private final ShopOrderPaymentService service = rig.service;

    private ShopOrder order(long id) {
        ShopOrder o = ShopPaymentTestRig.createdAt(ShopOrderTestOrders.newModeOrder(id, UID, 0L, CheckoutSource.CART),
                Instant.now().minus(Duration.ofMinutes(5)));
        when(rig.orders.findByPublicTokenAndUserId("tok" + id, UID)).thenReturn(Optional.of(o));
        when(rig.orders.findById(id)).thenReturn(Optional.of(o));
        PaymentIntent pi = ShopPaymentTestRig.intent(id * 10, "pi-" + id, o.getCreatedAt(), "gw-" + id,
                Instant.now().plus(Duration.ofMinutes(60)));
        when(rig.intents.findByShopOrderId(id)).thenReturn(List.of(pi));
        return o;
    }

    @Test
    @DisplayName("首次 → 逐个 reconcile 已出码未付意图；10 秒内第二次 → 不调网关，直接回当前状态")
    void throttledSecondCallSkipsGateway() {
        order(1L);
        when(rig.valueOps.setIfAbsent(eq("shop:payment-check:tok1"), eq("1"), eq(Duration.ofSeconds(10))))
                .thenReturn(true, false);
        when(rig.reconciliation.reconcile("pi-1")).thenReturn(PaymentStatus.PENDING);

        PaymentCheckView first = service.paymentCheck(UID, "tok1");
        PaymentCheckView second = service.paymentCheck(UID, "tok1");

        verify(rig.reconciliation, org.mockito.Mockito.times(1)).reconcile("pi-1");
        assertThat(first.paid()).isFalse();
        assertThat(second).isEqualTo(new PaymentCheckView(false, "PENDING_PAYMENT", "PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("订单非待付款（已付）→ 不查网关、不占节流键，paid=true")
    void nonPendingDoesNotQuery() {
        ShopOrder o = order(2L);
        o.markPaid(Instant.now());

        PaymentCheckView v = service.paymentCheck(UID, "tok2");

        assertThat(v.paid()).isTrue();
        assertThat(v.status()).isEqualTo("PENDING_SHIPMENT");
        verify(rig.reconciliation, never()).reconcile(anyString());
        verify(rig.valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("查单失败（UNKNOWN / 异常）不报错，返回 paid=false")
    void unknownReturnsNotPaid() {
        order(3L);
        when(rig.valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(rig.reconciliation.reconcile("pi-3")).thenThrow(new IllegalStateException("gateway"));

        PaymentCheckView v = service.paymentCheck(UID, "tok3");

        assertThat(v.paid()).isFalse();
        assertThat(v.displayStatus()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("已取消单 paid=false；宽限期待付款 displayStatus=CANCELLED")
    void viewSemantics() {
        ShopOrder o = order(4L);
        ShopPaymentTestRig.createdAt(o, Instant.now().minus(Duration.ofMinutes(61)));
        assertThat(PaymentCheckView.of(o, Instant.now()))
                .isEqualTo(new PaymentCheckView(false, "PENDING_PAYMENT", "CANCELLED"));
        o.markCancelled(com.tailtopia.shop.order.refund.CancelReason.PAYMENT_TIMEOUT, Instant.now());
        assertThat(PaymentCheckView.of(o, Instant.now()).paid()).isFalse();
    }
}

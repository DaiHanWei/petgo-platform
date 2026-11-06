package com.tailtopia.shop.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：网关到账（v1.4.2 shop Story 3.2 AC4.3 / AC6.1 / AC6.4）。 */
class ShopOrderPaidHandlerTest {

    private final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    private final ShopOrderPaymentService payments = mock(ShopOrderPaymentService.class);
    private final CheckoutService checkout = mock(CheckoutService.class);
    private final com.tailtopia.pay.service.PaymentIntentService paymentIntents =
            mock(com.tailtopia.pay.service.PaymentIntentService.class);
    private final ShopPaymentAlerts alerts = mock(ShopPaymentAlerts.class);
    private final ShopOrderPaidHandler handler = new ShopOrderPaidHandler(orders, payments, checkout,
            paymentIntents, alerts);

    private static PaymentIntentPaidEvent paid(PaymentPurpose purpose) {
        return new PaymentIntentPaidEvent(1L, "pi-1", 9L, purpose, PayChannel.MIXED, 220_000L, "IDR");
    }

    @Test
    @DisplayName("🔴 新单到账：markPaid(GATEWAY)，不再扣币（S-3：币在提交时已扣）")
    void newOrderDoesNotDebit() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(1L, 9L, 30_000L, CheckoutSource.CART);
        when(orders.findByPaymentIntentToken("pi-1")).thenReturn(Optional.of(o));
        when(payments.markPaid(o, PaidSource.GATEWAY)).thenReturn(true);

        handler.onPaid(paid(PaymentPurpose.SHOP_ORDER));

        verify(payments).markPaid(o, PaidSource.GATEWAY);
        verify(checkout, never()).settlePawCoinSegment(any());
    }

    @Test
    @DisplayName("🔴 过渡：存量单（checkout_source IS NULL）到账仍按旧规则扣币")
    void legacyStockOrderStillDebits() {
        ShopOrder o = ShopOrderTestOrders.legacyStockOrder(2L, 9L, 30_000L);
        when(orders.findByPaymentIntentToken("pi-1")).thenReturn(Optional.of(o));
        when(payments.markPaid(o, PaidSource.GATEWAY)).thenReturn(true);

        handler.onPaid(paid(PaymentPurpose.SHOP_ORDER));

        verify(checkout).settlePawCoinSegment(o);
    }

    @Test
    @DisplayName("订单已非待支付（已取消 / 已处理）→ 不扣币（v1.4.2 shop 3.3：改记迟到款，见 ShopOrderPaidHandlerLateTest）")
    void alreadyHandledSkips() {
        ShopOrder o = ShopOrderTestOrders.legacyStockOrder(3L, 9L, 30_000L);
        o.markCancelled(com.tailtopia.shop.order.refund.CancelReason.PAYMENT_TIMEOUT, java.time.Instant.now());
        when(orders.findByPaymentIntentToken("pi-1")).thenReturn(Optional.of(o));

        handler.onPaid(paid(PaymentPurpose.SHOP_ORDER));

        verify(payments, never()).markPaid(any(), any());
        verify(checkout, never()).settlePawCoinSegment(any());
    }

    @Test
    @DisplayName("其他 purpose / 找不到订单 → 不动、不抛")
    void otherPurposeAndMissingOrder() {
        handler.onPaid(paid(PaymentPurpose.GIFT));
        verifyNoInteractions(orders, payments, checkout);

        when(orders.findByPaymentIntentToken("pi-1")).thenReturn(Optional.empty());
        handler.onPaid(paid(PaymentPurpose.SHOP_ORDER));
        verifyNoInteractions(payments, checkout);
        verify(alerts).alert(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.TECH), org.mockito.ArgumentMatchers.contains("intentId=1"));
    }
}

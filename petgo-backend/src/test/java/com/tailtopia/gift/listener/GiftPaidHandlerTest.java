package com.tailtopia.gift.listener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftOrderStatus;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.gift.service.GiftDeliveryService;
import com.tailtopia.gift.service.GiftTestFixtures;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.gift.event.GiftUndeliveredEvent;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.4.0 gift Story 1.1 · AC8.4（L0）：只认 GIFT、重放幂等、任何路径不向上抛。 */
class GiftPaidHandlerTest {

    private GiftOrderRepository orders;
    private GiftDeliveryService delivery;
    private ApplicationEventPublisher events;
    private GiftPaidHandler handler;
    private GiftOrder order;

    @BeforeEach
    void setUp() {
        orders = mock(GiftOrderRepository.class);
        delivery = mock(GiftDeliveryService.class);
        events = mock(ApplicationEventPublisher.class);
        handler = new GiftPaidHandler(orders, delivery, events);
        order = GiftTestFixtures.order(41L, PayChannel.QRIS, 1, 10_000);
        order.attachPaymentIntent(99L);
        when(orders.findByPaymentIntentId(99L)).thenReturn(Optional.of(order));
    }

    private static PaymentIntentPaidEvent paid(PaymentPurpose purpose) {
        return new PaymentIntentPaidEvent(99L, "pi", 7L, purpose, PayChannel.QRIS, 10_000, "IDR");
    }

    @Test
    void pendingGiftOrderIsDelivered() {
        handler.onPaid(paid(PaymentPurpose.GIFT));
        verify(delivery).deliver(order);
    }

    @Test
    void nonGiftPurposeIsSkipped() {
        for (PaymentPurpose p : PaymentPurpose.values()) {
            if (p != PaymentPurpose.GIFT) {
                handler.onPaid(paid(p));
            }
        }
        verifyNoInteractions(orders, delivery, events);
    }

    @Test
    void nonPendingOrderIsSkipped() {
        ReflectionTestUtils.setField(order, "status", GiftOrderStatus.DELIVERED);
        handler.onPaid(paid(PaymentPurpose.GIFT));
        verify(delivery, never()).deliver(any());
    }

    /** OCR #7：订单已关（EXPIRED / FAILED）却到账 → 待退回 + 退回事件，不交付、不静默丢弃。 */
    @Test
    void paidAfterOrderClosedGoesToRefund() {
        for (GiftOrderStatus closed : new GiftOrderStatus[] {GiftOrderStatus.EXPIRED, GiftOrderStatus.FAILED}) {
            org.mockito.Mockito.clearInvocations(orders, events);
            ReflectionTestUtils.setField(order, "status", closed);
            when(orders.markRefundPendingIfClosed(41L)).thenReturn(1);

            handler.onPaid(paid(PaymentPurpose.GIFT));

            verify(orders).markRefundPendingIfClosed(41L);
            verify(events).publishEvent(eq(new GiftUndeliveredEvent(41L)));
        }
        verify(delivery, never()).deliver(any());
    }

    @Test
    void paidAfterCloseAlreadyHandledConcurrentlyPublishesNothing() {
        ReflectionTestUtils.setField(order, "status", GiftOrderStatus.EXPIRED);
        when(orders.markRefundPendingIfClosed(41L)).thenReturn(0);
        handler.onPaid(paid(PaymentPurpose.GIFT));
        verifyNoInteractions(events);
        verify(delivery, never()).deliver(any());
    }

    @Test
    void nonPendingNonClosedOrderIsNotRefunded() {
        ReflectionTestUtils.setField(order, "status", GiftOrderStatus.DELIVERED);
        handler.onPaid(paid(PaymentPurpose.GIFT));
        verify(orders, never()).markRefundPendingIfClosed(anyLong());
        verifyNoInteractions(events);
    }

    @Test
    void missingOrderIsSkipped() {
        when(orders.findByPaymentIntentId(anyLong())).thenReturn(Optional.empty());
        handler.onPaid(paid(PaymentPurpose.GIFT));
        verify(delivery, never()).deliver(any());
    }

    @Test
    void deliverFailureNeverPropagates() {
        when(delivery.deliver(any())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> handler.onPaid(paid(PaymentPurpose.GIFT))).doesNotThrowAnyException();

        when(orders.findByPaymentIntentId(anyLong())).thenThrow(new RuntimeException("repo down"));
        assertThatCode(() -> handler.onPaid(paid(PaymentPurpose.GIFT))).doesNotThrowAnyException();
    }
}

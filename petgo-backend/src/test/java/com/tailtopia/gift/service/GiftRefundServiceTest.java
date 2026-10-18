package com.tailtopia.gift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftOrderStatus;
import com.tailtopia.gift.event.GiftRefundedEvent;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PawCoinWalletService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.4.0 gift Story 1.2 · AC1（L0）：退回只入账一次、状态守卫、失败计次与 REFUND_MANUAL。 */
class GiftRefundServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    private GiftOrderRepository orders;
    private PawCoinWalletService wallet;
    private ApplicationEventPublisher events;
    private GiftRefundService service;
    private GiftOrder order;

    @BeforeEach
    void setUp() {
        orders = mock(GiftOrderRepository.class);
        wallet = mock(PawCoinWalletService.class);
        events = mock(ApplicationEventPublisher.class);
        service = new GiftRefundService(orders, wallet, events, Clock.fixed(NOW, ZoneOffset.UTC));
        order = GiftTestFixtures.order(41L, PayChannel.QRIS, 5, 5_000);
        ReflectionTestUtils.setField(order, "status", GiftOrderStatus.UNDELIVERED_REFUND_PENDING);
        when(orders.findByIdForUpdate(41L)).thenReturn(Optional.of(order));
    }

    @Test
    void pendingOrderIsCreditedOnceToSenderAndMarkedRefunded() {
        assertThat(service.attempt(41L)).isTrue();

        verify(wallet, times(1)).credit(GiftTestFixtures.SENDER, 25_000L, PawCoinTxnType.TOPUP, "GIFT_REFUND", 41L,
                "GIFT_REFUND:TOK41");
        assertThat(order.getStatus()).isEqualTo(GiftOrderStatus.UNDELIVERED_REFUNDED);
        assertThat(order.getRefundedAt()).isEqualTo(NOW);
        assertThat(order.getRefundError()).isNull();
        verify(orders).save(order);
        verify(events).publishEvent(new GiftRefundedEvent(41L));
    }

    @Test
    void refundIsLockedReadAndUsesTheCentralIdemKey() {
        service.attempt(41L);
        verify(orders).findByIdForUpdate(41L);
        verify(orders, never()).findById(anyLong());
        assertThat(GiftIdemKeys.refund("TOK41")).isEqualTo("GIFT_REFUND:TOK41");
    }

    @Test
    void nonPendingOrderIsNotCredited() {
        for (GiftOrderStatus s : GiftOrderStatus.values()) {
            if (s == GiftOrderStatus.UNDELIVERED_REFUND_PENDING) {
                continue;
            }
            ReflectionTestUtils.setField(order, "status", s);
            assertThat(service.attempt(41L)).isFalse();
        }
        verifyNoInteractions(wallet, events);
        verify(orders, never()).save(any());
    }

    @Test
    void missingOrderIsNoop() {
        assertThat(service.attempt(404L)).isFalse();
        service.recordFailure(404L, "X");
        verifyNoInteractions(wallet, events);
    }

    @Test
    void secondAttemptAfterSuccessIsIdempotent() {
        service.attempt(41L);
        service.attempt(41L);
        verify(wallet, times(1)).credit(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString());
        verify(events, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void creditFailurePropagatesWithoutStateChangeOrEvent() {
        doThrow(new IllegalStateException("db down")).when(wallet)
                .credit(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString());

        assertThatThrownBy(() -> service.attempt(41L)).isInstanceOf(IllegalStateException.class);

        assertThat(order.getStatus()).isEqualTo(GiftOrderStatus.UNDELIVERED_REFUND_PENDING);
        verify(orders, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void recordFailureCountsUpToFourThenFifthGoesManual() {
        for (int i = 1; i <= 4; i++) {
            service.recordFailure(41L, "IllegalStateException");
            assertThat(order.getRefundAttempts()).isEqualTo(i);
            assertThat(order.getStatus()).isEqualTo(GiftOrderStatus.UNDELIVERED_REFUND_PENDING);
        }
        service.recordFailure(41L, "IllegalStateException");
        assertThat(order.getRefundAttempts()).isEqualTo(5);
        assertThat(order.getStatus()).isEqualTo(GiftOrderStatus.REFUND_MANUAL);
        assertThat(order.getRefundError()).isEqualTo("IllegalStateException");

        // REFUND_MANUAL 之后再记失败 / 再尝试都不动
        service.recordFailure(41L, "Other");
        assertThat(order.getRefundAttempts()).isEqualTo(5);
        assertThat(service.attempt(41L)).isFalse();
        verifyNoInteractions(wallet);
        verify(orders, times(5)).save(order);
    }

    @Test
    void refundErrorStoresOnlyClassNameTruncatedTo64() {
        String longName = "X".repeat(80);
        service.recordFailure(41L, longName);
        assertThat(order.getRefundError()).hasSize(64);
    }

    @Test
    void recordFailureOnlyActsOnPending() {
        ReflectionTestUtils.setField(order, "status", GiftOrderStatus.UNDELIVERED_REFUNDED);
        service.recordFailure(41L, "X");
        assertThat(order.getRefundAttempts()).isZero();
        verify(orders, never()).save(any());
    }
}

package com.tailtopia.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakePurchase;
import com.tailtopia.purchase.domain.KeepsakePurchaseStatus;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.event.KeepsakeUnlockedEvent;
import com.tailtopia.purchase.repository.KeepsakePurchaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 3.1 · AC5（L0）：到账唯一监听器与失败收尾。 */
class KeepsakePaidHandlerTest {

    private KeepsakePurchaseRepository repo;
    private KeepsakeGrantRunner grants;
    private ApplicationEventPublisher events;
    private KeepsakePaidHandler handler;
    private KeepsakePurchase row;

    @BeforeEach
    void setUp() {
        repo = mock(KeepsakePurchaseRepository.class);
        grants = mock(KeepsakeGrantRunner.class);
        events = mock(ApplicationEventPublisher.class);
        row = KeepsakePurchase.pendingQris("tok", new KeepsakeRef(KeepsakeSku.TAILSONALITY, 5, "r", 70L, false), 7L,
                5000, 99L, Instant.EPOCH);
        ReflectionTestUtils.setField(row, "id", 1L);
        when(repo.findByPaymentIntentId(99L)).thenReturn(Optional.of(row));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        handler = new KeepsakePaidHandler(repo, grants, events,
                Clock.fixed(Instant.parse("2026-09-30T08:00:00Z"), ZoneOffset.UTC));
    }

    private static PaymentIntentPaidEvent paid(PaymentPurpose purpose, long amount) {
        return new PaymentIntentPaidEvent(99L, "pi", 7L, purpose, PayChannel.QRIS, amount, "IDR");
    }

    @Test
    void paidGrantsOnceAndReplayIsIdempotent() {
        when(grants.grantOrNull(any())).thenReturn(GrantOutcome.GRANTED);
        handler.onPaid(paid(PaymentPurpose.TAILSONALITY, 5000));
        handler.onPaid(paid(PaymentPurpose.TAILSONALITY, 5000));
        assertThat(row.getStatus()).isEqualTo(KeepsakePurchaseStatus.PAID);
        assertThat(row.getPaidAt()).isEqualTo(Instant.parse("2026-09-30T08:00:00Z"));
        verify(grants, times(1)).grantOrNull(row);
        verify(events, times(1)).publishEvent(new KeepsakeUnlockedEvent(7L, KeepsakeSku.TAILSONALITY, 5L, 5000L,
                PayChannel.QRIS));
    }

    @Test
    void priceIsOverwrittenByTheArrivedAmount() {
        when(grants.grantOrNull(any())).thenReturn(GrantOutcome.GRANTED);
        handler.onPaid(paid(PaymentPurpose.TAILSONALITY, 4800));
        assertThat(row.getPriceIdr()).isEqualTo(4800);
    }

    @Test
    void alreadyUnlockedBecomesDuplicatePaidAndMissingRefOrphanPaid() {
        when(grants.grantOrNull(any())).thenReturn(GrantOutcome.ALREADY_UNLOCKED);
        handler.onPaid(paid(PaymentPurpose.TAILSONALITY, 5000));
        assertThat(row.getStatus()).isEqualTo(KeepsakePurchaseStatus.DUPLICATE_PAID);

        row.markStatus(KeepsakePurchaseStatus.PENDING);
        when(grants.grantOrNull(any())).thenReturn(GrantOutcome.REF_MISSING);
        handler.onPaid(paid(PaymentPurpose.TAILSONALITY, 5000));
        assertThat(row.getStatus()).isEqualTo(KeepsakePurchaseStatus.ORPHAN_PAID);
        verify(events, never()).publishEvent(any(KeepsakeUnlockedEvent.class));
    }

    @Test
    void grantFailureBecomesOrphanPaidAndNeverThrows() {
        when(grants.grantOrNull(any())).thenReturn(null);
        assertThatCode(() -> handler.onPaid(paid(PaymentPurpose.TAILSONALITY, 5000))).doesNotThrowAnyException();
        assertThat(row.getStatus()).isEqualTo(KeepsakePurchaseStatus.ORPHAN_PAID);
    }

    @Test
    void repositoryFailureNeverPropagatesSoMarkPaidIsNotRolledBack() {
        when(repo.findByPaymentIntentId(anyLong())).thenThrow(new IllegalStateException("db"));
        assertThatCode(() -> handler.onPaid(paid(PaymentPurpose.BOARDING_PASS, 1000))).doesNotThrowAnyException();
    }

    @Test
    void unknownIntentAndOtherPurposesAreIgnored() {
        when(repo.findByPaymentIntentId(99L)).thenReturn(Optional.empty());
        assertThatCode(() -> handler.onPaid(paid(PaymentPurpose.PASSPORT_SNAP, 2000))).doesNotThrowAnyException();
        handler.onPaid(paid(PaymentPurpose.ID_HD, 5000));
        verify(grants, never()).grantOrNull(any());
    }

    @Test
    void failedEventClosesOnlyPendingRowsByCategory() {
        KeepsakePaymentFailedHandler failed = new KeepsakePaymentFailedHandler(repo);
        failed.onFailed(new PaymentIntentFailedEvent(99L, "pi", 7L, PaymentPurpose.TAILSONALITY, PayChannel.QRIS, 5000,
                "IDR", PaymentFailureCategory.EXPIRED));
        assertThat(row.getStatus()).isEqualTo(KeepsakePurchaseStatus.EXPIRED);

        row.markStatus(KeepsakePurchaseStatus.PENDING);
        failed.onFailed(new PaymentIntentFailedEvent(99L, "pi", 7L, PaymentPurpose.TAILSONALITY, PayChannel.QRIS, 5000,
                "IDR", PaymentFailureCategory.GATEWAY_DECLINED));
        assertThat(row.getStatus()).isEqualTo(KeepsakePurchaseStatus.CANCELED);

        row.markPaid(5000, Instant.EPOCH);
        failed.onFailed(new PaymentIntentFailedEvent(99L, "pi", 7L, PaymentPurpose.TAILSONALITY, PayChannel.QRIS, 5000,
                "IDR", PaymentFailureCategory.USER_CANCELLED));
        assertThat(row.getStatus()).isEqualTo(KeepsakePurchaseStatus.PAID);

        failed.onFailed(new PaymentIntentFailedEvent(99L, "pi", 7L, PaymentPurpose.ID_HD, PayChannel.QRIS, 5000,
                "IDR", PaymentFailureCategory.EXPIRED));
        assertThat(row.getStatus()).isEqualTo(KeepsakePurchaseStatus.PAID);
    }
}

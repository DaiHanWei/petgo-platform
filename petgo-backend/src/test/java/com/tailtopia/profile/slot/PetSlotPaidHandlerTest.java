package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * L0：V1.4.0 multi-pet Story 2.2 · AC6 + code review #1 / #2 / #3 —— QRIS 到账：非本 purpose 忽略、已结算重放幂等、
 * 发放成功 → PAID + 回填名额 + 发 {@link PetSlotGrantedEvent}（reconcile 提交后另开事务）；发放失败（保存点已回滚）→ ORPHAN_PAID；
 * 会超 5 → OVERPAID 且不发事件；EXPIRED / FAILED 行（同键重开被替换 / 失败回调）到账照常发放；任何异常不上抛、本类不调 reconcile。
 */
class PetSlotPaidHandlerTest {

    private static final long USER = 7L;
    private static final Instant PAID_AT = Instant.parse("2026-10-28T03:00:00Z");

    private final PetSlotPurchaseRepository purchases = mock(PetSlotPurchaseRepository.class);
    private final PetSlotGranter granter = mock(PetSlotGranter.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private PetSlotPaidHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PetSlotPaidHandler(purchases, granter, events, Clock.fixed(PAID_AT, ZoneOffset.UTC));
    }

    private PaymentIntentPaidEvent event(PaymentPurpose purpose) {
        return new PaymentIntentPaidEvent(99L, "pi", USER, purpose, PayChannel.QRIS, 10_000L, "IDR");
    }

    private PetSlotPurchase row(PetSlotPurchaseKind kind) {
        PetSlotPurchase p = PetSlotPurchase.pending("pt", USER, kind, 2, 5000, 10_000L, PayChannel.QRIS, 99L,
                "k:99", kind == PetSlotPurchaseKind.RENEW ? List.of(11L, 12L) : null, PAID_AT.minus(Duration.ofMinutes(5)));
        ReflectionTestUtils.setField(p, "id", 1L);
        when(purchases.findByPaymentIntentId(99L)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void annotatedAsSameTransactionSyncListener() throws NoSuchMethodException {
        Method m = PetSlotPaidHandler.class.getMethod("onPaid", PaymentIntentPaidEvent.class);
        assertThat(m.getAnnotation(EventListener.class)).isNotNull();
        assertThat(m.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void handlerNoLongerDependsOnReconcileOrJpaGrant() {
        // code review #1：reconcile 移出到账事务；发放只经保存点包裹的 PetSlotGranter。
        assertThat(java.util.Arrays.stream(PetSlotPaidHandler.class.getDeclaredFields()).map(f -> f.getType()))
                .doesNotContain((Class) PetSlotStateService.class, (Class) PetSlotLock.class,
                        (Class) PetSlotPurchaseService.class);
    }

    @Test
    void otherPurposeIgnored() {
        handler.onPaid(event(PaymentPurpose.TAILSONALITY));
        verifyNoInteractions(purchases, granter, events);
    }

    @Test
    void missingRowWarnsAndReturns() {
        when(purchases.findByPaymentIntentId(99L)).thenReturn(Optional.empty());
        assertThatCode(() -> handler.onPaid(event(PaymentPurpose.PET_SLOT_RENT))).doesNotThrowAnyException();
        verifyNoInteractions(granter, events);
    }

    @ParameterizedTest
    @EnumSource(value = PetSlotPurchaseStatus.class, names = {"PAID", "ORPHAN_PAID", "OVERPAID"})
    void settledRowIsReplayNoop(PetSlotPurchaseStatus settled) {
        PetSlotPurchase p = row(PetSlotPurchaseKind.RENT);
        p.settle(settled, PAID_AT);
        handler.onPaid(event(PaymentPurpose.PET_SLOT_RENT));
        verifyNoInteractions(granter, events);
        verify(purchases, never()).save(any());
    }

    @Test
    void grantedRentSettlesPaidBackfillsRentalsAndPublishesReconcileEvent() {
        PetSlotPurchase p = row(PetSlotPurchaseKind.RENT);
        when(granter.grant(p, PAID_AT)).thenReturn(new PetSlotGranter.Result(PetSlotGranter.Outcome.GRANTED,
                List.of(501L, 502L), PetSlotTrigger.RENTAL_ADDED));
        handler.onPaid(event(PaymentPurpose.PET_SLOT_RENT));
        assertThat(p.getStatus()).isEqualTo(PetSlotPurchaseStatus.PAID);
        assertThat(p.getPaidAt()).isEqualTo(PAID_AT);
        assertThat(p.rentalIdList()).containsExactly(501L, 502L);
        verify(purchases).save(p);
        ArgumentCaptor<PetSlotGrantedEvent> ev = ArgumentCaptor.forClass(PetSlotGrantedEvent.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isEqualTo(new PetSlotGrantedEvent(USER, PetSlotTrigger.RENTAL_ADDED));
    }

    @Test
    void grantedRenewPublishesRenewed() {
        PetSlotPurchase p = row(PetSlotPurchaseKind.RENEW);
        when(granter.grant(p, PAID_AT)).thenReturn(new PetSlotGranter.Result(PetSlotGranter.Outcome.GRANTED,
                List.of(11L, 503L), PetSlotTrigger.RENEWED));
        handler.onPaid(event(PaymentPurpose.PET_SLOT_RENT));
        assertThat(p.rentalIdList()).containsExactly(11L, 503L);
        verify(events).publishEvent(new PetSlotGrantedEvent(USER, PetSlotTrigger.RENEWED));
    }

    @Test
    void grantFailureSettlesOrphanPaidAndDoesNotPropagate() {
        PetSlotPurchase p = row(PetSlotPurchaseKind.RENT);
        when(granter.grant(p, PAID_AT)).thenThrow(new IllegalStateException("insert failed"));
        assertThatCode(() -> handler.onPaid(event(PaymentPurpose.PET_SLOT_RENT))).doesNotThrowAnyException();
        assertThat(p.getStatus()).isEqualTo(PetSlotPurchaseStatus.ORPHAN_PAID);
        assertThat(p.getPaidAt()).isEqualTo(PAID_AT);
        assertThat(p.rentalIdList()).isEmpty();
        verify(purchases).save(p);
        verifyNoInteractions(events);
    }

    @Test
    void overCapSettlesOverpaidWithoutSlotsOrEvent() {
        PetSlotPurchase p = row(PetSlotPurchaseKind.RENT);
        when(granter.grant(p, PAID_AT)).thenReturn(new PetSlotGranter.Result(PetSlotGranter.Outcome.OVER_CAP,
                List.of(), PetSlotTrigger.RENTAL_ADDED));
        handler.onPaid(event(PaymentPurpose.PET_SLOT_RENT));
        assertThat(p.getStatus()).isEqualTo(PetSlotPurchaseStatus.OVERPAID);
        assertThat(p.rentalIdList()).isEmpty();
        verify(purchases).save(p);
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @EnumSource(value = PetSlotPurchaseStatus.class, names = {"EXPIRED", "FAILED"})
    void replacedOrFailedRowStillGrantsWhenItsIntentPays(PetSlotPurchaseStatus st) {
        PetSlotPurchase p = row(PetSlotPurchaseKind.RENT);
        p.markStatus(st); // code review #3：同键重开时旧行被置 EXPIRED，旧二维码仍付成功
        when(granter.grant(p, PAID_AT)).thenReturn(new PetSlotGranter.Result(PetSlotGranter.Outcome.GRANTED,
                List.of(601L, 602L), PetSlotTrigger.RENTAL_ADDED));
        handler.onPaid(event(PaymentPurpose.PET_SLOT_RENT));
        assertThat(p.getStatus()).isEqualTo(PetSlotPurchaseStatus.PAID);
        assertThat(p.rentalIdList()).containsExactly(601L, 602L);
    }

    @Test
    void repositoryFailureDoesNotPropagate() {
        when(purchases.findByPaymentIntentId(99L)).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> handler.onPaid(event(PaymentPurpose.PET_SLOT_RENT))).doesNotThrowAnyException();
    }
}

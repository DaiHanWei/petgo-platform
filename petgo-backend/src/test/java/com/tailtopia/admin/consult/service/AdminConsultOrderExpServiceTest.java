package com.tailtopia.admin.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.consult.service.AdminConsultOrderExpService.ConsultExpState;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.growth.domain.GrowthLedgerEntry;
import com.tailtopia.growth.domain.ReverseReason;
import com.tailtopia.growth.repository.GrowthLedgerRepository;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.ReverseCommand;
import com.tailtopia.growth.service.ReverseOutcome;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** L0。问诊挂起退款成长值扣回的判定单点与执行（V1.4.2 exp-reverse Story 1.1）。 */
class AdminConsultOrderExpServiceTest {

    private static final long USER = 100L;
    private static final long ORDER_ID = 9L;
    private static final long INTENT = 42L;
    private static final String SPEND_KEY = "SPEND:PI:42";

    private final ConsultOrderRepository orders = mock(ConsultOrderRepository.class);
    private final GrowthLedgerService growth = mock(GrowthLedgerService.class);
    private final GrowthLedgerRepository ledger = mock(GrowthLedgerRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AdminConsultOrderExpService service =
            new AdminConsultOrderExpService(orders, growth, ledger, audit);

    private static ConsultOrder order(ConsultOrderStatus status, Instant suspensionRefundAt, Long intentId) {
        ConsultOrder o = ConsultOrder.inProgress("tok", USER, 7L, 3L, 50000L, PayChannel.QRIS, intentId,
                30000L, 60, 50000L, Instant.now());
        ReflectionTestUtils.setField(o, "id", ORDER_ID);
        ReflectionTestUtils.setField(o, "status", status);
        ReflectionTestUtils.setField(o, "suspensionRefundAt", suspensionRefundAt);
        return o;
    }

    private static ConsultOrder suspendedRefund() {
        return order(ConsultOrderStatus.REFUNDING, Instant.now(), INTENT);
    }

    private void accrued(String key, long userId, int delta, long reversed, long lifetime) {
        GrowthLedgerEntry e = GrowthLedgerEntry.of(555L, userId, "SPEND_DIGITAL", delta, "VET_CONSULT", "1", key);
        when(ledger.findByIdemKey(key)).thenReturn(Optional.of(e));
        when(ledger.sumReversed(555L)).thenReturn(reversed);
        when(growth.lifetimeTotal(userId)).thenReturn(lifetime);
    }

    @Test
    void suspendedQrisRefundWithAccrualIsReversible() {
        accrued(SPEND_KEY, USER, 50, 0, 1000);

        assertThat(service.state(suspendedRefund())).isEqualTo(new ConsultExpState(50, 0, 50, true));
    }

    @Test
    void noAccrualShowsNothingToReverse() {
        when(ledger.findByIdemKey(SPEND_KEY)).thenReturn(Optional.empty());

        assertThat(service.state(suspendedRefund())).isEqualTo(new ConsultExpState(null, 0, 0, false));
    }

    @Test
    void alreadyReversedIsNotReversibleEvenIfPartial() {
        accrued(SPEND_KEY, USER, 50, 20, 1000);

        assertThat(service.state(suspendedRefund())).isEqualTo(new ConsultExpState(50, 20, 0, false));
    }

    @Test
    void legacyOrderWithoutMarkIsNotReversible() {
        accrued(SPEND_KEY, USER, 50, 0, 1000);

        assertThat(service.state(order(ConsultOrderStatus.REFUNDING, null, INTENT)).reversible()).isFalse();
    }

    @Test
    void regularRefundingOrderIsNotReversible() {
        accrued(SPEND_KEY, USER, 50, 0, 1000);

        ConsultExpState s = service.state(order(ConsultOrderStatus.REFUNDING, null, INTENT));

        assertThat(s.accrued()).isEqualTo(50);
        assertThat(s.pending()).isZero();
    }

    @Test
    void refundedOrderIsNotReversible() {
        accrued(SPEND_KEY, USER, 50, 0, 1000);

        assertThat(service.state(order(ConsultOrderStatus.REFUNDED, Instant.now(), INTENT)).reversible()).isFalse();
    }

    @Test
    void anotherUsersEntryIsNeverUsed() {
        accrued(SPEND_KEY, 999L, 50, 0, 1000);

        assertThat(service.state(suspendedRefund()).accrued()).isNull();
    }

    @Test
    void pawcoinOrderLooksUpByOrderKeyNotIntent() {
        accrued("SPEND:COIN:vet-consult-order:9", USER, 30, 0, 1000);

        assertThat(service.state(order(ConsultOrderStatus.REFUNDING, Instant.now(), null)).accrued()).isEqualTo(30);
    }

    @Test
    void pendingCappedByLifetime() {
        accrued(SPEND_KEY, USER, 50, 0, 20);

        assertThat(service.state(suspendedRefund())).isEqualTo(new ConsultExpState(50, 0, 20, true));
    }

    @Test
    void zeroLifetimeIsNotReversible() {
        accrued(SPEND_KEY, USER, 50, 0, 0);

        assertThat(service.state(suspendedRefund())).isEqualTo(new ConsultExpState(50, 0, 0, false));
    }

    private void loadOrder(ConsultOrder o) {
        when(orders.findByOrderToken("tok")).thenReturn(Optional.of(o));
    }

    @Test
    void reverseUsesRefundSourceAndAuditsFigures() {
        loadOrder(suspendedRefund());
        accrued(SPEND_KEY, USER, 50, 0, 1000);
        when(growth.reverseDetailed(any())).thenReturn(ReverseOutcome.done(50, 4, 3));

        assertThat(service.reverse("tok", 8L)).isEqualTo(50);

        ArgumentCaptor<ReverseCommand> cmd = ArgumentCaptor.forClass(ReverseCommand.class);
        verify(growth).reverseDetailed(cmd.capture());
        assertThat(cmd.getValue().originalIdemKey()).isEqualTo(SPEND_KEY);
        assertThat(cmd.getValue().reason()).isEqualTo(ReverseReason.REFUND);
        assertThat(cmd.getValue().sourceKey()).isEqualTo("REFUND:consult-refund-9");
        assertThat(cmd.getValue().amountOverride()).isNull();
        verify(audit).record(8L, AuditActions.CONSULT_ORDER_EXP_REVERSED, "consult_order", "tok",
                "accrued=50 reversedBefore=0 reversed=50 levelBefore=4 levelAfter=3");
    }

    @Test
    void reverseReturnsActualAmountWhenCapped() {
        loadOrder(suspendedRefund());
        accrued(SPEND_KEY, USER, 50, 0, 20);
        when(growth.reverseDetailed(any())).thenReturn(ReverseOutcome.done(20, 2, 1));

        assertThat(service.reverse("tok", 8L)).isEqualTo(20);
    }

    private void assertRejected(String code) {
        assertThatThrownBy(() -> service.reverse("tok", 8L))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(422);
                    assertThat(e.getMessageCode()).isEqualTo(code);
                });
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void reverseRejectsAlreadyReversed() {
        loadOrder(suspendedRefund());
        accrued(SPEND_KEY, USER, 50, 50, 1000);

        assertRejected("admin.err.consultExp.alreadyReversed");
        verify(growth, never()).reverseDetailed(any());
    }

    @Test
    void reverseLosingConcurrentRaceReportsAlreadyReversed() {
        loadOrder(suspendedRefund());
        accrued(SPEND_KEY, USER, 50, 0, 1000);
        when(growth.reverseDetailed(any())).thenReturn(ReverseOutcome.alreadyReversed());

        assertRejected("admin.err.consultExp.alreadyReversed");
    }

    @Test
    void reverseRejectsNonEligibleOrder() {
        loadOrder(order(ConsultOrderStatus.REFUNDING, null, INTENT));
        accrued(SPEND_KEY, USER, 50, 0, 1000);

        assertRejected("admin.err.consultExp.notReversible");
        verify(growth, never()).reverseDetailed(any());
    }

    @Test
    void reverseRejectsWhenNothingLeftToTake() {
        loadOrder(suspendedRefund());
        accrued(SPEND_KEY, USER, 50, 0, 1000);
        when(growth.reverseDetailed(any())).thenReturn(ReverseOutcome.nothingToReverse());

        assertRejected("admin.err.consultExp.notReversible");
    }

    @Test
    void reverseUnknownOrderIsNotFound() {
        when(orders.findByOrderToken(eq("tok"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reverse("tok", 8L)).isInstanceOf(AppException.class);
    }
}

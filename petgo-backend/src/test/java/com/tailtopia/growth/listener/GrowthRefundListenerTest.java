package com.tailtopia.growth.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.GrowthLedgerEntry;
import com.tailtopia.growth.domain.ReverseReason;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.ReverseCommand;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.RefundSucceededEvent;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** V1.4.0 ① Story 1.3 · AC5 / AC6（L0）：定位规则、按比例扣回额、sourceKey。 */
class GrowthRefundListenerTest {

    private final GrowthLedgerService ledger = mock(GrowthLedgerService.class);
    private final GrowthRefundListener listener = new GrowthRefundListener(ledger);

    private static GrowthLedgerEntry spend(String key, int delta) {
        return GrowthLedgerEntry.of(1L, 7L, "SPEND_SHOP", delta, "SHOP_ORDER", "11", key);
    }

    private ReverseCommand reversed() {
        ArgumentCaptor<ReverseCommand> c = ArgumentCaptor.forClass(ReverseCommand.class);
        verify(ledger).reverse(c.capture());
        return c.getValue();
    }

    @Test
    void consultCashIsLocatedByIntentKey() {
        when(ledger.findByIdemKey("SPEND:PI:42")).thenReturn(Optional.of(spend("SPEND:PI:42", 250)));

        listener.onRefunded(new RefundSucceededEvent("rf_1", 7L, PaymentPurpose.VET_CONSULT, "CONSULT_ORDER", "9",
                42L, 50_000, 50_000));

        ReverseCommand r = reversed();
        assertThat(r.originalIdemKey()).isEqualTo("SPEND:PI:42");
        assertThat(r.amountOverride()).isEqualTo(250);
        assertThat(r.reason()).isEqualTo(ReverseReason.REFUND);
        assertThat(r.sourceKey()).isEqualTo("REFUND:rf_1");
        verify(ledger, never()).findSpendByRef(anyString(), anyString());
    }

    @Test
    void consultCoinIsLocatedByOrderRef() {
        when(ledger.findByIdemKey("SPEND:COIN:vet-consult-order:9"))
                .thenReturn(Optional.of(spend("SPEND:COIN:vet-consult-order:9", 250)));

        listener.onRefunded(new RefundSucceededEvent("consult-refund-9", 7L, PaymentPurpose.VET_CONSULT,
                "CONSULT_ORDER", "9", null, 50_000, 50_000));

        assertThat(reversed().originalIdemKey()).isEqualTo("SPEND:COIN:vet-consult-order:9");
    }

    @Test
    void shopPartialRefundsAreProportional() {
        when(ledger.findByIdemKey("SPEND:SHOP:11")).thenReturn(Optional.of(spend("SPEND:SHOP:11", 100)));

        listener.onRefunded(new RefundSucceededEvent("ret_1", 7L, PaymentPurpose.SHOP_ORDER, "SHOP_ORDER", "11",
                null, 60_000, 200_000));

        ReverseCommand r = reversed();
        assertThat(r.originalIdemKey()).isEqualTo("SPEND:SHOP:11");
        assertThat(r.amountOverride()).isEqualTo(30);
        assertThat(GrowthRefundListener.proportional(100, 140_000, 200_000)).isEqualTo(70);
    }

    @Test
    void shopWithIntentStillUsesOrderRef() {
        when(ledger.findByIdemKey("SPEND:SHOP:11")).thenReturn(Optional.of(spend("SPEND:SHOP:11", 100)));

        listener.onRefunded(new RefundSucceededEvent("ret_1", 7L, PaymentPurpose.SHOP_ORDER, "SHOP_ORDER", "11",
                99L, 200_000, 200_000));

        assertThat(reversed().amountOverride()).isEqualTo(100);
        verify(ledger, never()).findByIdemKey("SPEND:PI:99");
    }

    @Test
    void anotherUsersEntryIsNeverReversed() {
        GrowthLedgerEntry someoneElse = GrowthLedgerEntry.of(1L, 8L, "SPEND_DIGITAL", 250, "VET_CONSULT", "42",
                "SPEND:COIN:vet-consult-order:42");
        when(ledger.findByIdemKey("SPEND:COIN:vet-consult-order:42")).thenReturn(Optional.of(someoneElse));

        listener.onRefunded(new RefundSucceededEvent("consult-refund-42", 7L, PaymentPurpose.VET_CONSULT,
                "CONSULT_ORDER", "42", null, 50_000, 50_000));

        verify(ledger, never()).reverse(any());
    }

    @Test
    void otherPurposesFallBackToRefLookup() {
        when(ledger.findSpendByRef("AI_UNLOCK", "5")).thenReturn(Optional.of(
                GrowthLedgerEntry.of(1L, 7L, "SPEND_DIGITAL", 50, "AI_UNLOCK", "5", "SPEND:COIN:ai-unlock:5")));

        listener.onRefunded(new RefundSucceededEvent("r", 7L, PaymentPurpose.AI_UNLOCK, "AI_TASK", "5", null,
                10_000, 10_000));

        assertThat(reversed().originalIdemKey()).isEqualTo("SPEND:COIN:ai-unlock:5");
    }

    @Test
    void missingOriginalIsNotReversed() {
        when(ledger.findByIdemKey(anyString())).thenReturn(Optional.empty());

        listener.onRefunded(new RefundSucceededEvent("ret_1", 7L, PaymentPurpose.SHOP_ORDER, "SHOP_ORDER", "11",
                null, 60_000, 200_000));

        verify(ledger, never()).reverse(any());
    }

    @Test
    void nonPositiveAmountsAreSkipped() {
        listener.onRefunded(new RefundSucceededEvent("x", 7L, PaymentPurpose.SHOP_ORDER, "SHOP_ORDER", "11", null,
                60_000, 0));
        listener.onRefunded(new RefundSucceededEvent("y", 7L, PaymentPurpose.SHOP_ORDER, "SHOP_ORDER", "11", null,
                0, 200_000));

        verify(ledger, never()).findByIdemKey(anyString());
        verify(ledger, never()).reverse(any());
    }

    @Test
    void proportionalNeverExceedsOriginal() {
        assertThat(GrowthRefundListener.proportional(100, 300_000, 200_000)).isEqualTo(100);
        assertThat(GrowthRefundListener.proportional(5, 1, 3)).isEqualTo(2); // 1.67 → 2
    }
}

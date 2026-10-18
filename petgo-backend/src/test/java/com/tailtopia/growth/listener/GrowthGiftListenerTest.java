package com.tailtopia.growth.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.gift.event.GiftSentEvent;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthLedgerService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** V1.4.0 ① Story 1.1 · AC15（L0）：送礼两条命令与换算金额。 */
class GrowthGiftListenerTest {

    private final GrowthLedgerService ledger = mock(GrowthLedgerService.class);
    private final GrowthGiftListener listener = new GrowthGiftListener(ledger);

    @Test
    void senderAndReceiverOwnerBothAccrueByAmount() {
        when(ledger.pointsForAmount(RuleCode.GIFT_SENT, 12_500)).thenReturn(63);
        when(ledger.pointsForAmount(RuleCode.GIFT_RECEIVED, 12_500)).thenReturn(50);

        listener.onGiftSent(new GiftSentEvent(4L, 3L, 7L, 9L, 12_500));

        ArgumentCaptor<AccrualCommand> c = ArgumentCaptor.forClass(AccrualCommand.class);
        verify(ledger, times(2)).accrue(c.capture());
        List<AccrualCommand> cs = c.getAllValues();
        assertThat(cs.get(0).userId()).isEqualTo(3L);
        assertThat(cs.get(0).rule()).isEqualTo(RuleCode.GIFT_SENT);
        assertThat(cs.get(0).pointsOverride()).isEqualTo(63);
        assertThat(cs.get(0).idemKey()).isEqualTo("GIFT_SENT:4");
        assertThat(cs.get(1).userId()).isEqualTo(7L);
        assertThat(cs.get(1).rule()).isEqualTo(RuleCode.GIFT_RECEIVED);
        assertThat(cs.get(1).pointsOverride()).isEqualTo(50);
        assertThat(cs.get(1).idemKey()).isEqualTo("GIFT_RECEIVED:4");
        assertThat(cs).allSatisfy(x -> {
            assertThat(x.refType()).isEqualTo("GIFT_ORDER");
            assertThat(x.refId()).isEqualTo("4");
            assertThat(x.petIds()).containsExactly(9L);
        });
    }

    @Test
    void zeroPointsIsNotAccrued() {
        when(ledger.pointsForAmount(any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(0);

        listener.onGiftSent(new GiftSentEvent(4L, 3L, 7L, 9L, 50));

        verify(ledger, never()).accrue(any());
    }
}

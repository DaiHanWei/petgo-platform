package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** L0：V1.4.0 multi-pet Story 2.2 · AC7.1 —— 失败事件映射 EXPIRED / FAILED；非 PENDING 不动；非本 purpose 忽略；不碰名额。 */
class PetSlotPaymentFailedHandlerTest {

    private final PetSlotPurchaseRepository purchases = mock(PetSlotPurchaseRepository.class);
    private final PetSlotPaymentFailedHandler handler = new PetSlotPaymentFailedHandler(purchases);

    private static PaymentIntentFailedEvent event(PaymentPurpose purpose, PaymentFailureCategory category) {
        return new PaymentIntentFailedEvent(99L, "pi", 7L, purpose, PayChannel.QRIS, 5000L, "IDR", category);
    }

    private PetSlotPurchase row() {
        PetSlotPurchase p = PetSlotPurchase.pending("pt", 7L, PetSlotPurchaseKind.RENT, 1, 5000, 5000, PayChannel.QRIS,
                99L, "k", null, Instant.now());
        when(purchases.findByPaymentIntentId(99L)).thenReturn(Optional.of(p));
        return p;
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"EXPIRED, EXPIRED", "GATEWAY_DECLINED, FAILED", "USER_CANCELLED, FAILED"})
    void mapsCategoryToTerminalStatus(PaymentFailureCategory category, PetSlotPurchaseStatus expected) {
        PetSlotPurchase p = row();
        handler.onFailed(event(PaymentPurpose.PET_SLOT_RENT, category));
        assertThat(p.getStatus()).isEqualTo(expected);
        verify(purchases).save(p);
    }

    @Test
    void nonPendingRowUntouched() {
        PetSlotPurchase p = row();
        p.markPaid(Instant.now());
        handler.onFailed(event(PaymentPurpose.PET_SLOT_RENT, PaymentFailureCategory.EXPIRED));
        assertThat(p.getStatus()).isEqualTo(PetSlotPurchaseStatus.PAID);
        verify(purchases, never()).save(any());
    }

    @Test
    void otherPurposeOrNullCategoryIgnored() {
        handler.onFailed(event(PaymentPurpose.TAILSONALITY, PaymentFailureCategory.EXPIRED));
        handler.onFailed(event(PaymentPurpose.PET_SLOT_RENT, null));
        verifyNoInteractions(purchases);
    }
}

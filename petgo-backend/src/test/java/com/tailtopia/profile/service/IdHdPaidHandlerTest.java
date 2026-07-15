package com.tailtopia.profile.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/** L0：ID_HD 到账处理器只处理 ID_HD purpose，其余忽略（照 AiUnlockPaidHandler 范式）。 */
class IdHdPaidHandlerTest {

    private final IdCardHdService service = mock(IdCardHdService.class);
    private final IdHdPaidHandler handler = new IdHdPaidHandler(service);

    private static PaymentIntentPaidEvent event(PaymentPurpose purpose) {
        return new PaymentIntentPaidEvent(7L, "tok", 42L, purpose, PayChannel.QRIS, 5000L, "IDR");
    }

    @Test
    void handlesIdHd() {
        handler.onPaid(event(PaymentPurpose.ID_HD));
        verify(service).completePurchase(42L, PayChannel.QRIS, 7L);
    }

    @Test
    void ignoresOtherPurposes() {
        handler.onPaid(event(PaymentPurpose.PAWCOIN_TOPUP));
        handler.onPaid(event(PaymentPurpose.AI_UNLOCK));
        handler.onPaid(event(PaymentPurpose.VET_CONSULT));
        verify(service, never()).completePurchase(
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.anyLong());
    }
}

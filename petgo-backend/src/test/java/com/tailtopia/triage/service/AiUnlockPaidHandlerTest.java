package com.tailtopia.triage.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0 —— Story 2.3 {@link AiUnlockPaidHandler} 到账事件分派：仅 {@code AI_UNLOCK} 委派 completeCashUnlock，
 * 其余 purpose 忽略（不 crash、不误解锁）。
 */
@ExtendWith(MockitoExtension.class)
class AiUnlockPaidHandlerTest {

    @Mock
    private AiUnlockService aiUnlockService;
    @InjectMocks
    private AiUnlockPaidHandler handler;

    private static PaymentIntentPaidEvent event(PaymentPurpose purpose) {
        return new PaymentIntentPaidEvent(1L, "inttok", 7L, purpose, PayChannel.QRIS, 10000L, "IDR");
    }

    @Test
    void aiUnlockPurposeDelegatesToCompleteCashUnlock() {
        handler.onPaid(event(PaymentPurpose.AI_UNLOCK));
        verify(aiUnlockService).completeCashUnlock("inttok");
    }

    @Test
    void topupPurposeIgnored() {
        handler.onPaid(event(PaymentPurpose.PAWCOIN_TOPUP));
        verify(aiUnlockService, never()).completeCashUnlock(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void vetConsultPurposeIgnored() {
        handler.onPaid(event(PaymentPurpose.VET_CONSULT));
        verify(aiUnlockService, never()).completeCashUnlock(org.mockito.ArgumentMatchers.anyString());
    }
}

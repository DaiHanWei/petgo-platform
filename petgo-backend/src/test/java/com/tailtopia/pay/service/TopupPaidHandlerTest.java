package com.tailtopia.pay.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0：到账 handler 分派（mock 钱包 service）。PAWCOIN_TOPUP 到账 → credit(TOPUP) 一次；其余 purpose 不 credit。
 * （AFTER_COMMIT 禁用、同事务原子性由 @EventListener + Propagation.MANDATORY 保证，属 L1/代码审查。）
 */
@ExtendWith(MockitoExtension.class)
class TopupPaidHandlerTest {

    @Mock
    PawCoinWalletService walletService;

    private PaymentIntentPaidEvent event(PaymentPurpose purpose) {
        return new PaymentIntentPaidEvent(100L, "tok-1", 7L, purpose, PayChannel.QRIS, 25_000L, "IDR");
    }

    @Test
    void topupPaidCreditsOnceWithIntentTokenAsIdempotencyKey() {
        new TopupPaidHandler(walletService).onPaid(event(PaymentPurpose.PAWCOIN_TOPUP));
        verify(walletService).credit(7L, 25_000L, PawCoinTxnType.TOPUP, "PAYMENT_INTENT", 100L, "tok-1");
    }

    @Test
    void nonTopupPurposeDoesNotCredit() {
        new TopupPaidHandler(walletService).onPaid(event(PaymentPurpose.VET_CONSULT));
        new TopupPaidHandler(walletService).onPaid(event(PaymentPurpose.AI_UNLOCK));
        verify(walletService, never()).credit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }
}

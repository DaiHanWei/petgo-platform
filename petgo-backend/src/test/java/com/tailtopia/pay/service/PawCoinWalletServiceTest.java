package com.tailtopia.pay.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PawCoinTransaction;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.repository.LedgerEntryRepository;
import com.tailtopia.pay.repository.PawCoinTransactionRepository;
import com.tailtopia.pay.repository.PawCoinWalletRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：钱包 service（mock repo/ledger/idempotency）。余额不足拒绝、成功路径三写（钱包/总账/流水）、幂等短路。
 */
@ExtendWith(MockitoExtension.class)
class PawCoinWalletServiceTest {

    @Mock
    PawCoinWalletRepository wallets;
    @Mock
    PawCoinTransactionRepository txns;
    @Mock
    LedgerEntryRepository ledger;
    @Mock
    LedgerService ledgerService;
    @Mock
    IdempotencyService idempotency;

    private PawCoinWalletService service() {
        return new PawCoinWalletService(wallets, txns, ledger, ledgerService, idempotency);
    }

    private void stubTxnSave() {
        when(txns.save(any(PawCoinTransaction.class))).thenAnswer(inv -> {
            PawCoinTransaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 9L);
            return t;
        });
    }

    @Test
    void debitInsufficientRejectsAndDoesNotPostOrWrite() {
        when(idempotency.findResourceId("k")).thenReturn(Optional.empty());
        when(wallets.applyDelta(1L, -500L)).thenReturn(0); // 原子条件 UPDATE 命中 0 行 = 余额不足

        assertThatThrownBy(() -> service().debit(1L, 500L, PawCoinTxnType.SPEND, "AI", 3L, "k"))
                .isInstanceOf(AppException.class);

        verify(ledgerService, never()).post(anyString(), any(), anyString());
        verify(txns, never()).save(any());
    }

    @Test
    void creditSuccessWritesWalletLedgerAndTxn() {
        when(idempotency.findResourceId("k")).thenReturn(Optional.empty());
        when(wallets.applyDelta(1L, 10000L)).thenReturn(1);
        stubTxnSave();

        service().credit(1L, 10000L, PawCoinTxnType.TOPUP, "TOPUP_ORDER", 7L, "k");

        verify(wallets).insertIfAbsent(1L);
        verify(wallets).applyDelta(1L, 10000L);
        verify(ledgerService).post(anyString(), any(List.class), eq("k"));
        verify(txns).save(any(PawCoinTransaction.class));
        verify(idempotency).store(eq("k"), anyLong());
    }

    @Test
    void debitSuccessWritesWalletLedgerAndTxn() {
        when(idempotency.findResourceId("k")).thenReturn(Optional.empty());
        when(wallets.applyDelta(1L, -3000L)).thenReturn(1);
        stubTxnSave();

        service().debit(1L, 3000L, PawCoinTxnType.SPEND, "AI", 3L, "k");

        verify(wallets).applyDelta(1L, -3000L);
        verify(ledgerService).post(anyString(), any(List.class), eq("k"));
        verify(txns).save(any(PawCoinTransaction.class));
    }

    @Test
    void idempotentReplayShortCircuits() {
        when(idempotency.findResourceId("k")).thenReturn(Optional.of(9L));

        service().credit(1L, 10000L, PawCoinTxnType.TOPUP, "TOPUP_ORDER", 7L, "k");

        verify(wallets, never()).applyDelta(anyLong(), anyLong());
        verify(ledgerService, never()).post(anyString(), any(), anyString());
        verify(txns, never()).save(any());
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> service().credit(1L, 0L, PawCoinTxnType.TOPUP, "x", 1L, "k"))
                .isInstanceOf(AppException.class);
    }
}

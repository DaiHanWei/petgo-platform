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
        when(idempotency.findResourceId("wallet:k")).thenReturn(Optional.empty());
        when(wallets.applyDelta(1L, -500L)).thenReturn(0); // 原子条件 UPDATE 命中 0 行 = 余额不足

        assertThatThrownBy(() -> service().debit(1L, 500L, PawCoinTxnType.SPEND, "AI", 3L, "k"))
                .isInstanceOf(AppException.class);

        verify(ledgerService, never()).post(anyString(), any(), anyString());
        verify(txns, never()).save(any());
    }

    @Test
    void creditSuccessWritesWalletLedgerAndTxn() {
        when(idempotency.findResourceId("wallet:k")).thenReturn(Optional.empty());
        when(wallets.applyDelta(1L, 10000L)).thenReturn(1);
        stubTxnSave();

        service().credit(1L, 10000L, PawCoinTxnType.TOPUP, "TOPUP_ORDER", 7L, "k");

        verify(wallets).insertIfAbsent(1L);
        verify(wallets).applyDelta(1L, 10000L);
        verify(ledgerService).post(anyString(), any(List.class), eq("k"));
        verify(txns).save(any(PawCoinTransaction.class));
        verify(idempotency).store(eq("wallet:k"), anyLong());
    }

    @Test
    void debitSuccessWritesWalletLedgerAndTxn() {
        when(idempotency.findResourceId("wallet:k")).thenReturn(Optional.empty());
        when(wallets.applyDelta(1L, -3000L)).thenReturn(1);
        stubTxnSave();

        service().debit(1L, 3000L, PawCoinTxnType.SPEND, "AI", 3L, "k");

        verify(wallets).applyDelta(1L, -3000L);
        verify(ledgerService).post(anyString(), any(List.class), eq("k"));
        verify(txns).save(any(PawCoinTransaction.class));
    }

    @Test
    void idempotentReplayShortCircuits() {
        when(idempotency.findResourceId("wallet:k")).thenReturn(Optional.of(9L));

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

    @Test
    void crossTtlReplayShortCircuitsViaLedgerDbFallback() {
        // Review P2：Redis 键已过期(空)，但总账 DB 仍有该幂等键 → 必须在改钱包之前短路，
        // 杜绝跨 TTL 二次入账（钱包翻倍/双扣）。
        when(idempotency.findResourceId("wallet:k")).thenReturn(Optional.empty());
        when(ledger.findFirstByIdempotencyKey("k"))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(
                        com.tailtopia.pay.domain.LedgerEntry.class)));

        service().credit(1L, 10000L, PawCoinTxnType.TOPUP, "TOPUP_ORDER", 7L, "k");

        verify(wallets, never()).insertIfAbsent(anyLong());
        verify(wallets, never()).applyDelta(anyLong(), anyLong());
        verify(ledgerService, never()).post(anyString(), any(), anyString());
        verify(txns, never()).save(any());
    }

    @Test
    void qrisIntentMappingUnderSameBusinessKeyDoesNotShortCircuitDebit() {
        // bug 20260929-578：KTP 先开 QRIS（PaymentIntentService 往共享 idem: 空间写「id-hd-card:5 → 意图 42」）不付，
        // 再改 PawCoin 用同一业务键扣币。旧实现读到这条意图映射就当「已扣过」短路 → 不扣币白拿。
        // 钱包现在只认自己 wallet: 前缀下的映射 + 总账，意图映射不再影响扣币。
        org.mockito.Mockito.lenient().when(idempotency.findResourceId("id-hd-card:5")).thenReturn(Optional.of(42L));
        when(idempotency.findResourceId("wallet:id-hd-card:5")).thenReturn(Optional.empty());
        when(wallets.applyDelta(1L, -2000L)).thenReturn(1);
        stubTxnSave();

        service().debit(1L, 2000L, PawCoinTxnType.SPEND, "ID_HD", 5L, "id-hd-card:5");

        verify(wallets).applyDelta(1L, -2000L);
        verify(ledgerService).post(anyString(), any(List.class), eq("id-hd-card:5"));
        verify(idempotency).store(eq("wallet:id-hd-card:5"), anyLong());
    }

    @Test
    void walletKeyIsNamespacedAndLeavesBlankUntouched() {
        org.assertj.core.api.Assertions.assertThat(PawCoinWalletService.walletKey("ai-unlock:7")).isEqualTo("wallet:ai-unlock:7");
        org.assertj.core.api.Assertions.assertThat(PawCoinWalletService.walletKey(null)).isNull();
        org.assertj.core.api.Assertions.assertThat(PawCoinWalletService.walletKey("")).isEmpty();
    }
}

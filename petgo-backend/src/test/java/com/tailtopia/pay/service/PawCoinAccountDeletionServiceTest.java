package com.tailtopia.pay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.LedgerAccount;
import com.tailtopia.pay.domain.LedgerDirection;
import com.tailtopia.pay.domain.PawCoinWallet;
import com.tailtopia.pay.repository.PawCoinTransactionRepository;
import com.tailtopia.pay.repository.PawCoinWalletRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：注销 PawCoin 作废（mock repo/ledger）。b&gt;0 写 FLOAT_LIABILITY DEBIT / FORFEITURE CREDIT 平衡终结分录 +
 * 归零 + 物删钱包/流水；b==0 不写分录仅幂等物删。
 */
@ExtendWith(MockitoExtension.class)
class PawCoinAccountDeletionServiceTest {

    @Mock
    PawCoinWalletRepository wallets;
    @Mock
    PawCoinTransactionRepository txns;
    @Mock
    LedgerService ledgerService;
    @Captor
    ArgumentCaptor<List<LedgerLine>> linesCaptor;

    private PawCoinAccountDeletionService service() {
        return new PawCoinAccountDeletionService(wallets, txns, ledgerService);
    }

    private PawCoinWallet walletWithBalance(long userId, long balance) {
        PawCoinWallet w = PawCoinWallet.forUser(userId);
        ReflectionTestUtils.setField(w, "balance", balance);
        return w;
    }

    @Test
    void positiveBalanceWritesForfeitureLedgerZeroesAndPurges() {
        when(wallets.findByUserId(7L)).thenReturn(Optional.of(walletWithBalance(7L, 5000L)));

        service().voidBalanceAndPurge(7L);

        // 终结分录：一组平衡分录 FLOAT_LIABILITY DEBIT 5000 / FORFEITURE CREDIT 5000，稳定幂等键。
        verify(ledgerService).post(anyString(), linesCaptor.capture(), eq("acct-del-forfeit:7"));
        List<LedgerLine> lines = linesCaptor.getValue();
        assertThat(lines).hasSize(2);
        LedgerLine debit = lines.stream()
                .filter(l -> l.direction() == LedgerDirection.DEBIT).findFirst().orElseThrow();
        LedgerLine credit = lines.stream()
                .filter(l -> l.direction() == LedgerDirection.CREDIT).findFirst().orElseThrow();
        assertThat(debit.account()).isEqualTo(LedgerAccount.FLOAT_LIABILITY);
        assertThat(debit.amount()).isEqualTo(5000L);
        assertThat(debit.userId()).isEqualTo(7L);
        assertThat(credit.account()).isEqualTo(LedgerAccount.FORFEITURE);
        assertThat(credit.amount()).isEqualTo(5000L);
        assertThat(credit.userId()).isNull(); // FORFEITURE 非用户维度

        // 归零 + 物删钱包/流水。
        verify(wallets).applyDelta(7L, -5000L);
        verify(txns).deleteByUserId(7L);
        verify(wallets).deleteByUserId(7L);
    }

    @Test
    void zeroBalanceSkipsLedgerButStillPurges() {
        when(wallets.findByUserId(7L)).thenReturn(Optional.empty()); // 无钱包 = 余额 0

        service().voidBalanceAndPurge(7L);

        verify(ledgerService, never()).post(anyString(), any(), anyString());
        verify(wallets, never()).applyDelta(anyLong(), anyLong());
        // 幂等物删仍执行（可能删 0 行）。
        verify(txns).deleteByUserId(7L);
        verify(wallets).deleteByUserId(7L);
    }
}

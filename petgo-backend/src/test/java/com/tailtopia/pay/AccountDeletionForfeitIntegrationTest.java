package com.tailtopia.pay;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.pay.domain.LedgerAccount;
import com.tailtopia.pay.domain.LedgerDirection;
import com.tailtopia.pay.domain.LedgerEntry;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.repository.LedgerEntryRepository;
import com.tailtopia.pay.repository.PawCoinTransactionRepository;
import com.tailtopia.pay.repository.PawCoinWalletRepository;
import com.tailtopia.pay.service.PawCoinAccountDeletionService;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * L1：注销 PawCoin 作废（Story 1.6，FR-50D）。有余额注销 → 写 FORFEITURE 终结分录归零 + 物删钱包/流水 +
 * 总账保留 + reconcile 一致；零余额不炸；幂等重跑收敛。净库 petgo_l1（见记忆库清库法）。
 */
class AccountDeletionForfeitIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PawCoinWalletService walletService;
    @Autowired
    private PawCoinAccountDeletionService pawCoinDeletion;
    @Autowired
    private PawCoinWalletRepository wallets;
    @Autowired
    private PawCoinTransactionRepository txns;
    @Autowired
    private LedgerEntryRepository ledger;

    @Test
    void positiveBalanceForfeitsPurgesAndReconciles() {
        User u = newUser();
        long uid = u.getId();
        // 造余额：充值 5000 koin。
        walletService.credit(uid, 5000L, PawCoinTxnType.TOPUP, "TOPUP_ORDER", 1L, "topup:" + uid);
        assertThat(walletService.balanceOf(uid)).isEqualTo(5000L);

        pawCoinDeletion.voidBalanceAndPurge(uid);

        // 个人钱包/流水物理删净。
        assertThat(wallets.findByUserId(uid)).isEmpty();
        assertThat(txns.findByUserIdOrderByCreatedAtDesc(uid, PageRequest.of(0, 10))).isEmpty();

        // 终结分录保留在 append-only 总账：一组 FLOAT_LIABILITY DEBIT 5000 / FORFEITURE CREDIT 5000。
        LedgerEntry any = ledger.findFirstByIdempotencyKey("acct-del-forfeit:" + uid).orElseThrow();
        List<LedgerEntry> group = ledger.findByEntryGroup(any.getEntryGroup());
        assertThat(group).hasSize(2);
        assertThat(group).anyMatch(e -> e.getAccount() == LedgerAccount.FLOAT_LIABILITY
                && e.getDirection() == LedgerDirection.DEBIT && e.getAmount() == 5000L);
        assertThat(group).anyMatch(e -> e.getAccount() == LedgerAccount.FORFEITURE
                && e.getDirection() == LedgerDirection.CREDIT && e.getAmount() == 5000L);

        // 对账一致：钱包删=0，FLOAT_LIABILITY 净额（充值 CREDIT 5000 − 作废 DEBIT 5000）=0。
        PawCoinWalletService.ReconcileResult rec = walletService.reconcile(uid);
        assertThat(rec.walletBalance()).isZero();
        assertThat(rec.ledgerNet()).isZero();
        assertThat(rec.consistent()).isTrue();
    }

    @Test
    void zeroBalanceAndRepeatAreIdempotent() {
        User u = newUser();
        long uid = u.getId();

        // 从未充值（无钱包）→ 作废不报错、不写分录。
        pawCoinDeletion.voidBalanceAndPurge(uid);
        assertThat(wallets.findByUserId(uid)).isEmpty();
        assertThat(ledger.findFirstByIdempotencyKey("acct-del-forfeit:" + uid)).isEmpty();

        // 再造余额 + 作废 + 二次作废（模拟 rescan 残留重跑）：收敛、不双写、不炸。
        walletService.credit(uid, 3000L, PawCoinTxnType.TOPUP, "TOPUP_ORDER", 2L, "topup2:" + uid);
        pawCoinDeletion.voidBalanceAndPurge(uid);
        pawCoinDeletion.voidBalanceAndPurge(uid); // 幂等：钱包已删 → 跳分录 → 删 0 行

        assertThat(wallets.findByUserId(uid)).isEmpty();
        LedgerEntry any = ledger.findFirstByIdempotencyKey("acct-del-forfeit:" + uid).orElseThrow();
        assertThat(ledger.findByEntryGroup(any.getEntryGroup())).hasSize(2); // 仅一组，未重复
        assertThat(walletService.reconcile(uid).consistent()).isTrue();
    }
}

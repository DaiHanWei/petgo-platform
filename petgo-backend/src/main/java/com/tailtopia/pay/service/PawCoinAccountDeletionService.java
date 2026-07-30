package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.LedgerAccount;
import com.tailtopia.pay.repository.PawCoinTransactionRepository;
import com.tailtopia.pay.repository.PawCoinWalletRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注销时 PawCoin 余额作废（Story 1.6，FR-50D「不提现/不转赠/注销余额作废」）。
 *
 * <p>account 编排（{@code AccountDeletionService}）调 {@link #voidBalanceAndPurge}；本 service 拥有 pay 表，
 * account 只调接口不 join（照 {@code ProfileDeletionService} 模式）。在<b>一个 {@code @Transactional}</b> 内：
 * <ol>
 *   <li>若余额 {@code b>0}：经 {@link LedgerService#post} 写终结分录 {@code FLOAT_LIABILITY DEBIT b}
 *       （冲平钱包镜像，令 {@code reconcile} 归零）/ {@code FORFEITURE CREDIT b}（作废沉没，<b>非平台营收</b>），
 *       并 {@code applyDelta(userId, -b)} 把余额归零；</li>
 *   <li>物理删 {@code pawcoin_transactions} + {@code pawcoin_wallets}（个人钱包/流水纳入级联删除路径）。</li>
 * </ol>
 *
 * <p>{@code ledger_entries} 终结分录 <b>append-only 保留</b>（对账/浮存审计留痕，{@code user_id} 无 FK、
 * user 行删后悬空为有意）。<b>幂等可重入</b>：钱包删后 {@code balance=0} 跳分录；{@link LedgerService} 的幂等键
 * {@code acct-del-forfeit:{userId}} + 唯一约束兜底重放（供 {@code rescanOnStartup} 续跑）。
 *
 * <p>不复用 {@code PawCoinWalletService.debit}：其贷方硬编码 {@code PLATFORM_REVENUE}（会污染营收）且会写一条
 * 随即被物理删的 {@code SPEND} 流水（无意义）。
 */
@Service
public class PawCoinAccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(PawCoinAccountDeletionService.class);
    private static final String REF_TYPE = "account_deletion";

    private final PawCoinWalletRepository wallets;
    private final PawCoinTransactionRepository txns;
    private final LedgerService ledgerService;

    public PawCoinAccountDeletionService(PawCoinWalletRepository wallets,
            PawCoinTransactionRepository txns, LedgerService ledgerService) {
        this.wallets = wallets;
        this.txns = txns;
        this.ledgerService = ledgerService;
    }

    /** 作废并清理该用户 PawCoin 余额（幂等）。见类注释。 */
    @Transactional
    public void voidBalanceAndPurge(long userId) {
        long balance = wallets.findByUserId(userId).map(w -> w.getBalance()).orElse(0L);
        if (balance > 0) {
            String group = UUID.randomUUID().toString();
            // 终结分录：FLOAT_LIABILITY DEBIT 冲平钱包镜像（reconcile 归零）；FORFEITURE CREDIT 作废沉没（非营收）。
            ledgerService.post(group, List.of(
                    LedgerLine.debit(LedgerAccount.FLOAT_LIABILITY, balance, userId, REF_TYPE, null),
                    LedgerLine.credit(LedgerAccount.FORFEITURE, balance, null, REF_TYPE, null)),
                    "acct-del-forfeit:" + userId);
            // 归零：WHERE balance + (-balance) >= 0 恰为 0，满足 balance>=0 CHECK。保崩溃中间态（分录已写未删行）
            // 下钱包与总账一致（重跑时 balance=0 跳分录）。
            wallets.applyDelta(userId, -balance);
        }
        int txnRows = txns.deleteByUserId(userId);
        int walletRows = wallets.deleteByUserId(userId);
        log.info("PawCoin 注销作废 userId={} forfeited={} txnRows={} walletRows={}",
                userId, balance, txnRows, walletRows);
    }
}

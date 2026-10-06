package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.LedgerAccount;
import com.tailtopia.pay.domain.LedgerDirection;
import com.tailtopia.pay.domain.PawCoinTransaction;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.repository.LedgerEntryRepository;
import com.tailtopia.pay.repository.PawCoinTransactionRepository;
import com.tailtopia.pay.repository.PawCoinWalletRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PawCoin 钱包 service（Story 1.2，兑现 FR-NFR-3 并发非负 + 钱包↔总账对账）。{@link #credit}/{@link #debit}
 * 在<b>同一 {@code @Transactional}</b> 内三件事原子提交：
 * <ol>
 *   <li>原子改钱包（{@code walletRepo.applyDelta}，非负由 {@code WHERE balance+delta>=0} + CHECK 兜底）；</li>
 *   <li>{@code LedgerService.post} 一组借贷平衡分录（{@code FLOAT_LIABILITY} 线镜像钱包变动，供对账）；</li>
 *   <li>写一条 {@code pawcoin_transactions} 用户流水。</li>
 * </ol>
 *
 * <p>幂等（Review P2 加固）：改钱包<b>之前</b>先判重放——Redis 前置 + <b>总账 DB 兜底</b>
 * （{@code findFirstByIdempotencyKey}，覆盖 Redis TTL 过期后的跨 TTL 重放）；并发同键由总账唯一约束
 * + 同事务回滚只成一次。稳定签名供 1.3 充值 / 2.x 消费 / 4.x 退款调用。
 */
@Service
public class PawCoinWalletService {

    private final PawCoinWalletRepository wallets;
    private final PawCoinTransactionRepository txns;
    private final LedgerEntryRepository ledger;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotency;

    public PawCoinWalletService(PawCoinWalletRepository wallets, PawCoinTransactionRepository txns,
            LedgerEntryRepository ledger, LedgerService ledgerService, IdempotencyService idempotency) {
        this.wallets = wallets;
        this.txns = txns;
        this.ledger = ledger;
        this.ledgerService = ledgerService;
        this.idempotency = idempotency;
    }

    /**
     * 入账（充值到账 / 退回 / 赠送）。{@code coins > 0}。首次自动建钱包（并发安全）。
     * 幂等：同键重放不重复入账。
     */
    @Transactional
    public void credit(long userId, long coins, PawCoinTxnType type, String refType, Long refId,
            String idempotencyKey) {
        requirePositive(coins);
        if (isReplay(idempotencyKey)) {
            return; // 已入账，幂等短路（Redis 前置 + 跨 TTL 的 DB 兜底，须在改钱包之前）
        }
        wallets.insertIfAbsent(userId); // 幂等建钱包（ON CONFLICT DO NOTHING）
        if (wallets.applyDelta(userId, coins) == 0) {
            // 入账不应失败（+coins 恒非负、钱包已建）；防御性拒绝。
            throw AppException.conflict("PawCoin 入账失败");
        }
        String group = UUID.randomUUID().toString();
        // FLOAT_LIABILITY CREDIT 镜像钱包 +coins；借方按来源科目平衡。
        ledgerService.post(group, List.of(
                LedgerLine.debit(creditCounterAccount(type), coins, null, refType, refId),
                LedgerLine.credit(LedgerAccount.FLOAT_LIABILITY, coins, userId, refType, refId)),
                idempotencyKey);
        PawCoinTransaction txn = txns.save(
                PawCoinTransaction.of(userId, coins, type, refType, refId, group));
        idempotency.store(walletKey(idempotencyKey), txn.getId());
    }

    /**
     * 扣减（消费）。{@code coins > 0}。余额不足 → {@link AppException#conflict}（原子条件 UPDATE 返回 0 行）。
     * 幂等：同键重放不重复扣减。
     */
    @Transactional
    public void debit(long userId, long coins, PawCoinTxnType type, String refType, Long refId,
            String idempotencyKey) {
        requirePositive(coins);
        if (isReplay(idempotencyKey)) {
            return; // 已扣减，幂等短路（Redis 前置 + 跨 TTL 的 DB 兜底，须在改钱包之前）
        }
        if (wallets.applyDelta(userId, -coins) == 0) {
            throw AppException.pawcoinInsufficient("PawCoin 余额不足");
        }
        String group = UUID.randomUUID().toString();
        // FLOAT_LIABILITY DEBIT 镜像钱包 -coins；贷方计平台收入。
        ledgerService.post(group, List.of(
                LedgerLine.debit(LedgerAccount.FLOAT_LIABILITY, coins, userId, refType, refId),
                LedgerLine.credit(LedgerAccount.PLATFORM_REVENUE, coins, null, refType, refId)),
                idempotencyKey);
        PawCoinTransaction txn = txns.save(
                PawCoinTransaction.of(userId, -coins, type, refType, refId, group));
        idempotency.store(walletKey(idempotencyKey), txn.getId());
    }

    /** 当前余额（无钱包记 0）。 */
    @Transactional(readOnly = true)
    public long balanceOf(long userId) {
        return wallets.findByUserId(userId).map(w -> w.getBalance()).orElse(0L);
    }

    /**
     * 对账（AC5）：钱包 {@code balance} 应等于其 {@code FLOAT_LIABILITY} 分录净额（CREDIT−DEBIT）。
     * 供 L1 断言与后台核对。
     */
    @Transactional(readOnly = true)
    public ReconcileResult reconcile(long userId) {
        long floatCredit = ledger.sumAmount(userId, LedgerAccount.FLOAT_LIABILITY, LedgerDirection.CREDIT);
        long floatDebit = ledger.sumAmount(userId, LedgerAccount.FLOAT_LIABILITY, LedgerDirection.DEBIT);
        long ledgerNet = floatCredit - floatDebit;
        long balance = balanceOf(userId);
        return new ReconcileResult(userId, balance, ledgerNet, balance == ledgerNet);
    }

    /** 入账来源科目：充值=现金流入、退款=退款流出、赠送=平台成本，均与 FLOAT_LIABILITY CREDIT 平衡。 */
    private static LedgerAccount creditCounterAccount(PawCoinTxnType type) {
        return switch (type) {
            case TOPUP -> LedgerAccount.CASH_IN;
            case REFUND -> LedgerAccount.REFUND_OUT;
            case BONUS -> LedgerAccount.PLATFORM_REVENUE;
            case SPEND -> throw AppException.validation("SPEND 不可用于入账");
        };
    }

    private static void requirePositive(long coins) {
        if (coins <= 0) {
            throw AppException.validation("金额必须为正");
        }
    }

    /**
     * 幂等重放判定（Review P2）：Redis 前置 + <b>总账 DB 兜底</b>。必须在改钱包（{@code applyDelta}）之前调用——
     * 否则 Redis TTL(24h) 过期后同键重放会二次改钱包（钱包翻倍/双扣）而总账被唯一约束去重，两者背离。
     * credit/debit 与其 {@code LedgerService.post} 共用同一 {@code idempotencyKey}，故 DB 侧查总账即权威。
     */
    private boolean isReplay(String idempotencyKey) {
        return idempotency.findResourceId(walletKey(idempotencyKey)).isPresent()
                || ledger.findFirstByIdempotencyKey(idempotencyKey).isPresent();
    }

    /**
     * 钱包专属的 Redis 幂等命名空间（bug 20260929-578）。
     *
     * <p>🔴 {@code IdempotencyService} 是全局共享的 {@code idem:} 键空间，{@code PaymentIntentService.createIntent}
     * 也往里写「业务键 → 意图 id」。各付费入口（KTP 高清 / AI 解锁 / 付费问诊）的 PawCoin 扣币与 QRIS 建意图
     * 曾共用同一业务键 ⇒ 用户先开 QRIS 不付、再改 PawCoin，{@code debit} 读到意图写的映射就当「已扣过」短路
     * —— 不扣币、调用方照常解锁。钱包的 Redis 映射只能由钱包自己写，故加前缀与意图隔离。
     *
     * <p>总账（{@code ledger_entries.idempotency_key}）仍用原键：它是跨 TTL 的权威兜底，且上线前已扣的
     * 流水靠它继续识别重放（不会因换前缀而二次扣费）。意图从不写总账，不存在同类串键。
     */
    static String walletKey(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank() ? idempotencyKey : "wallet:" + idempotencyKey;
    }

    /**
     * 对账结果。
     *
     * @param userId        用户
     * @param walletBalance 钱包实际余额
     * @param ledgerNet     总账 FLOAT_LIABILITY 净额（应等于余额）
     * @param consistent    是否一致
     */
    public record ReconcileResult(long userId, long walletBalance, long ledgerNet, boolean consistent) {
    }
}

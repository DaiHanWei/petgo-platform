package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.LedgerDirection;
import com.tailtopia.pay.domain.LedgerEntry;
import com.tailtopia.pay.repository.LedgerEntryRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 双分录总账 service（Story 1.2，兑现 FR-NFR-1）。{@link #post} 是一切资金事件记账的唯一入口：
 * <ul>
 *   <li><b>借贷平衡不变式</b>：Σ(DEBIT)==Σ(CREDIT) 且金额均 &gt; 0，否则 {@link AppException}（<b>不落库</b>）。</li>
 *   <li><b>幂等</b>：复用 {@link IdempotencyService} 前置 + 按 {@code idempotency_key} DB 查回（跨 TTL）——
 *       同键重放返回既有 {@code entry_group}、不重复记账；{@code (idempotency_key,account,direction)} 唯一约束库级兜底。</li>
 * </ul>
 *
 * <p>调用方（{@code PawCoinWalletService} / 1.3 充值 / 4.x 退款）在<b>各自的 {@code @Transactional}</b> 内调本方法，
 * 记账与钱包变动同事务原子提交。
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository ledger;
    private final IdempotencyService idempotency;

    public LedgerService(LedgerEntryRepository ledger, IdempotencyService idempotency) {
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    /**
     * 提交一组平衡分录。返回该组 {@code entryGroup}（重放时为既有组）。
     *
     * @param entryGroup     本组分录标识（调用方生成的 UUID 等）
     * @param lines          一组 {@code (account, direction, amount>0)}，须借贷平衡
     * @param idempotencyKey 幂等键（重放去重）
     */
    @Transactional
    public String post(String entryGroup, List<LedgerLine> lines, String idempotencyKey) {
        // 幂等前置（Redis）：已记账 → 返回既有组。
        Optional<Long> cached = idempotency.findResourceId(idempotencyKey);
        if (cached.isPresent()) {
            return ledger.findById(cached.get()).map(LedgerEntry::getEntryGroup).orElse(entryGroup);
        }
        // 跨 TTL 兜底：按幂等键查回既有组（不重复记账）。
        Optional<LedgerEntry> existing = ledger.findFirstByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get().getEntryGroup();
        }

        if (lines == null || lines.isEmpty()) {
            throw AppException.validation("分录不能为空");
        }
        long debit = 0;
        long credit = 0;
        for (LedgerLine l : lines) {
            if (l.amount() <= 0) {
                throw AppException.validation("分录金额必须为正");
            }
            if (l.direction() == LedgerDirection.DEBIT) {
                debit += l.amount();
            } else {
                credit += l.amount();
            }
        }
        if (debit != credit) {
            throw AppException.validation("双分录借贷不平衡"); // 不落库
        }

        // 落库（append-only）。同键重放/并发由 uq_ledger_entries_idem 唯一约束库级兜底。
        LedgerEntry first = null;
        for (LedgerLine l : lines) {
            LedgerEntry e = ledger.save(LedgerEntry.of(entryGroup, l.account(), l.direction(),
                    l.amount(), l.userId(), l.refType(), l.refId(), idempotencyKey));
            if (first == null) {
                first = e;
            }
        }
        idempotency.store(idempotencyKey, first.getId());
        return entryGroup;
    }
}

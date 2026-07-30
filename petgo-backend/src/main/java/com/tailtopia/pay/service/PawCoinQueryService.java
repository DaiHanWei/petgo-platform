package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.PawCoinTransaction;
import com.tailtopia.pay.dto.PawCoinTxnItem;
import com.tailtopia.pay.dto.PawCoinWalletView;
import com.tailtopia.pay.repository.PawCoinTransactionRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PawCoin 余额与流水只读查询（Story 1.4）。组合 {@link PawCoinWalletService#balanceOf}（余额）与
 * {@link PawCoinTransactionRepository#findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc}（游标分页流水，
 * 1.2 已预埋）。倒序游标分页范式照 {@code notify/service/NotificationCenterService.list}：limit+1 探 hasMore、
 * cursor=末条 epochMillis。<b>只读、只作用当前用户、不跨模块 join</b>。
 */
@Service
public class PawCoinQueryService {

    private final PawCoinWalletService walletService;
    private final PawCoinTransactionRepository txns;

    public PawCoinQueryService(PawCoinWalletService walletService, PawCoinTransactionRepository txns) {
        this.walletService = walletService;
        this.txns = txns;
    }

    /** 余额 + 倒序游标分页流水（cursor=上一页末条 epochMillis，首页 null）。 */
    @Transactional(readOnly = true)
    public PawCoinWalletView view(long userId, String cursor, int limit) {
        long balance = walletService.balanceOf(userId);
        Instant before = parseCursor(cursor);
        List<PawCoinTransaction> rows = txns.findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(
                userId, before, PageRequest.of(0, limit + 1)); // 多取 1 条判有无下一页
        boolean hasMore = rows.size() > limit;
        List<PawCoinTransaction> pageRows = hasMore ? rows.subList(0, limit) : rows;
        List<PawCoinTxnItem> items = pageRows.stream().map(PawCoinTxnItem::from).toList();
        String nextCursor = hasMore && !pageRows.isEmpty()
                ? String.valueOf(pageRows.get(pageRows.size() - 1).getCreatedAt().toEpochMilli())
                : null;
        return new PawCoinWalletView(balance, items, nextCursor, hasMore);
    }

    /** 首页 null/非法 → now+60s 余量（含并发新写）；否则末条 epochMillis。 */
    private static Instant parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Instant.now().plusSeconds(60);
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(cursor));
        } catch (NumberFormatException e) {
            return Instant.now().plusSeconds(60);
        }
    }
}

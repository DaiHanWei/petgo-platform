package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.PawCoinTransaction;
import com.tailtopia.pay.dto.PawCoinTxnItem;
import com.tailtopia.pay.dto.PawCoinWalletView;
import com.tailtopia.pay.repository.PawCoinTransactionRepository;
import com.tailtopia.shared.paging.KeysetCursor;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PawCoin 余额与流水只读查询（Story 1.4）。组合 {@link PawCoinWalletService#balanceOf}（余额）与
 * {@link PawCoinTransactionRepository#findPageBefore}（游标分页流水，
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

    /**
     * 余额 + 倒序游标分页流水。
     *
     * <p>游标是 {@link KeysetCursor}（base64url 的 {@code (createdAt, id)}，首页 null），
     * 对客户端<b>不透明</b>：原样回传，不解析。
     */
    @Transactional(readOnly = true)
    public PawCoinWalletView view(long userId, String cursor, int limit) {
        long balance = walletService.balanceOf(userId);
        KeysetCursor c = parseCursor(cursor);
        List<PawCoinTransaction> rows = txns.findPageBefore(
                userId, c.createdAt(), c.id(), PageRequest.of(0, limit + 1)); // 多取 1 条判有无下一页
        boolean hasMore = rows.size() > limit;
        List<PawCoinTransaction> pageRows = hasMore ? rows.subList(0, limit) : rows;
        List<PawCoinTxnItem> items = pageRows.stream().map(PawCoinTxnItem::from).toList();
        PawCoinTransaction last = pageRows.isEmpty() ? null : pageRows.get(pageRows.size() - 1);
        String nextCursor = hasMore && last != null
                ? new KeysetCursor(last.getCreatedAt(), last.getId()).encode()
                : null;
        return new PawCoinWalletView(balance, items, nextCursor, hasMore);
    }

    /** 首页 null/非法 → 首页哨兵（坏游标不该把用户挡在流水页外）；否则解出复合游标。 */
    private static KeysetCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return KeysetCursor.firstPage();
        }
        KeysetCursor parsed = KeysetCursor.decodeOrNull(cursor);
        if (parsed != null) {
            return parsed;
        }
        // 过渡兼容：老客户端手上还捏着旧格式（纯 epochMillis）的游标 —— 用 Long.MIN_VALUE
        // 让「同刻」分支恒不命中，行为与老实现逐字一致（仍会漏，但不报错、不错位）。
        try {
            return new KeysetCursor(Instant.ofEpochMilli(Long.parseLong(cursor.trim())),
                    Long.MIN_VALUE);
        } catch (NumberFormatException e) {
            return KeysetCursor.firstPage();
        }
    }
}

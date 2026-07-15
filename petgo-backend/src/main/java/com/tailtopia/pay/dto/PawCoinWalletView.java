package com.tailtopia.pay.dto;

import java.util.List;

/**
 * PawCoin 余额与流水视图（Story 1.4）。余额块 + 流水游标分页合体。架构格式 {@code {items, nextCursor,
 * hasMore}}（nextCursor=末条 epochMillis），外加 {@code balance}。
 *
 * @param balance    当前 PawCoin 余额（koin，1 koin=Rp1；无钱包→0）
 * @param items      本页流水（倒序）
 * @param nextCursor 下一页游标（末条 epochMillis 字符串；无更多→null）
 * @param hasMore    是否有下一页
 */
public record PawCoinWalletView(long balance, List<PawCoinTxnItem> items, String nextCursor,
        boolean hasMore) {
}

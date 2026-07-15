package com.tailtopia.order.dto;

import java.util.List;

/**
 * 订单中心分页视图（Story 5.1）。跨 3 源按 created_at 倒序合并 + 游标分页 + PawCoin 余额汇总。
 *
 * @param items          本页订单卡（倒序）
 * @param nextCursor     下一页游标（末条 createdAt epochMillis；无更多→null）
 * @param hasMore        是否有下一页
 * @param pawcoinBalance PawCoin 余额（koin，1:1；无钱包→0）
 */
public record OrderPage(List<OrderSummaryView> items, String nextCursor, boolean hasMore, long pawcoinBalance) {
}

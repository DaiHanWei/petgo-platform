package com.tailtopia.gift.web.dto;

import java.util.List;

/**
 * 我的送礼记录分页（V1.4.0 gift Story 2.3 · AC1.2），每页 20，{@code created_at DESC, id DESC}。
 *
 * @param nextCursor 下一页不透明游标（{@code shared.paging.KeysetCursor#encode()}）；没有下一页为 null（NON_NULL 省略）
 */
public record GiftOrderPage(List<GiftOrderRow> items, String nextCursor) {
}

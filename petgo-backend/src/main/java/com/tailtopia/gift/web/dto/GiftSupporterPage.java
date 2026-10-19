package com.tailtopia.gift.web.dto;

import java.util.List;

/**
 * 粉丝榜分页（V1.4.0 gift Story 2.1 · AC2.1，{@code GET /api/v1/pets/{petId}/gifts/supporters?cursor=}），每页 20。
 *
 * @param nextCursor 下一页不透明游标（{@link SupporterCursor#encode()}）；没有下一页为 null（NON_NULL 省略）
 */
public record GiftSupporterPage(List<GiftSupporterRow> items, String nextCursor) {
}

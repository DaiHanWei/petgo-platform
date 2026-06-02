package com.petgo.consult.dto;

import java.util.List;

/**
 * 问诊历史游标分页（Story 5.8）。架构格式 {@code {items, nextCursor, hasMore}}。
 * {@code nextCursor} 为最后一条的时间游标（epochMillis 字符串），{@code hasMore=false} 时为 null。
 */
public record ConsultHistoryPage(List<ConsultHistoryItem> items, String nextCursor, boolean hasMore) {
}

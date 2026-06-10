package com.tailtopia.profile.dto;

import java.util.List;

/**
 * 游标分页信封（Story 2.4，与 Feed 一致）。{@code nextCursor} 为下一页起始游标（ISO-8601 时刻字符串），
 * 末页为 null。
 */
public record TimelinePageResponse(
        List<TimelineItemResponse> items,
        String nextCursor,
        boolean hasMore) {
}

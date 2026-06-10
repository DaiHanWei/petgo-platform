package com.tailtopia.content.dto;

import java.util.List;

/**
 * 游标分页信封（Story 3.2）。统一 {@code {items, nextCursor, hasMore}}，camelCase、NON_NULL。
 * {@code nextCursor} 在 {@code hasMore=false} 时为 null（Jackson 省略）。
 */
public record FeedPageResponse(
        List<FeedItemResponse> items,
        String nextCursor,
        boolean hasMore) {
}

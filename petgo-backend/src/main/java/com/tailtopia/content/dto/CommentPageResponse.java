package com.petgo.content.dto;

import java.util.List;

/**
 * 评论游标分页信封（Story 3.3）。{@code {items, nextCursor, hasMore}}，camelCase、NON_NULL。
 */
public record CommentPageResponse(
        List<CommentResponse> items,
        String nextCursor,
        boolean hasMore) {
}

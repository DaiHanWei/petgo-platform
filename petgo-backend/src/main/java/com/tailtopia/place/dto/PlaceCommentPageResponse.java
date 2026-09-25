package com.tailtopia.place.dto;

import java.util.List;

/**
 * 场所评论游标分页信封（V1.3.0 batch-b1 Story 1.7）。形状与内容评论的
 * {@code CommentPageResponse} 一致（{@code items / nextCursor / hasMore}）—— 客户端的
 * 分页处理逻辑因此可以照抄，不用为场所另写一套。
 *
 * <p>🔴 带 {@code total}：详情页标题要写「KOMENTAR (3)」。
 * ⚠️ 它是**对当前查看者可见的条数**，与 items 的过滤条件逐字一致 ——
 * 否则会出现「标题写着 3 条、往下数只有 2 条」（拉黑过滤把一条滤掉了）。
 */
public record PlaceCommentPageResponse(
        List<PlaceCommentResponse> items,
        String nextCursor,
        boolean hasMore,
        long total) {
}

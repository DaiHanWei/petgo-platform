package com.tailtopia.social.dto;

import java.util.List;

/**
 * 推荐关注（V1.3.4 follow · Story 2.3 · AD-4）：最多 30 行，无分页、无游标。
 * 行复用 {@link FollowListItem}（{@code iFollow / self / blockedByMe / deleted} 恒 false，由查询 WHERE 保证）。
 */
public record FollowSuggestionsResponse(List<FollowListItem> items) {
}

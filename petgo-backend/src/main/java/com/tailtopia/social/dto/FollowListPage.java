package com.tailtopia.social.dto;

import java.util.List;

/** 关注 / 粉丝列表分页信封（Story 2.2）—— 形状同 {@code NotificationPage}：不透明游标、每页 20。 */
public record FollowListPage(List<FollowListItem> items, String nextCursor, boolean hasMore) {
}

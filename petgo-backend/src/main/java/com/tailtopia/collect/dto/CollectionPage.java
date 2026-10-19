package com.tailtopia.collect.dto;

import java.util.List;

/** 我的收藏分页信封（V1.4.0 ④ Story 2.2）—— 形状同 {@code FollowListPage}：不透明游标、每页 20；末页无 {@code nextCursor}。 */
public record CollectionPage<T>(List<T> items, String nextCursor, boolean hasMore) {
}

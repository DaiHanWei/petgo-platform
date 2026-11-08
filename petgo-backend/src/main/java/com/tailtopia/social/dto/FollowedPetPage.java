package com.tailtopia.social.dto;

import java.util.List;

/**
 * 「某用户追更的宠物」分页信封（V1.4.2 social-topic · Story 1.1 · AC8）—— 形状同 {@link FollowListPage}，
 * 多一个 {@code petFollowingCount}：<b>只在首页</b>（{@code cursor == null}）计算下发，后续页为 null（NON_NULL 省略）。
 */
public record FollowedPetPage(List<FollowedPetItem> items, String nextCursor, boolean hasMore,
        Long petFollowingCount) {
}

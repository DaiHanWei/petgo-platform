package com.tailtopia.content.dto;

import java.util.List;

/**
 * 游标分页信封（Story 3.2）。统一 {@code {items, nextCursor, hasMore}}，camelCase、NON_NULL。
 * {@code nextCursor} 在 {@code hasMore=false} 时为 null（Jackson 省略）。
 *
 * @param rankMode 本页用的排序路径（V1.1.6 Story 16.5）：{@value #RANK_MODE_RECOMMEND} /
 *                 {@value #RANK_MODE_CHRONO}；「我的发布」等非 Feed 出口为 {@code null}（省略）。
 *
 *                 <p>🔴 <b>必须由服务端下发，客户端推不出来</b>：降级链级别 4 会让 ALL Tab
 *                 <b>也走时间倒序</b>，而那对客户端完全无感。客户端按「是不是 ALL Tab」自己判断的话，
 *                 降级期间的数据会被算进推荐序的效果里 —— 而这正是 FR-95 参数校准要看的数。
 */
public record FeedPageResponse(
        List<FeedItemResponse> items,
        String nextCursor,
        boolean hasMore,
        String rankMode) {

    /** 推荐序（ALL Tab 正常态）。 */
    public static final String RANK_MODE_RECOMMEND = "recommend";

    /** 纯时间倒序（分类 Tab，以及 ALL Tab 降级到级别 4 时）。 */
    public static final String RANK_MODE_CHRONO = "chrono";
}

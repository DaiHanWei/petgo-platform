package com.tailtopia.place.dto;

import java.util.List;

/**
 * 场所列表信封（V1.3.0 batch-b1 Story 1.1 · AC2）。
 *
 * <p>{@code sortMode} 由**服务端**下发本页实际用的排序路径（{@value #SORT_MODE_RECENT} /
 * {@value #SORT_MODE_DISTANCE}）。
 *
 * <p>🔴 <b>客户端推不出来</b>：客户端带了坐标不等于服务端就走了距离分支（坐标非法、
 * 粗筛半径内一个场所都没有时都会回落到按最新）。客户端按「我有没有带坐标」自己判断的话，
 * 距离位会在回落时显示成一堆空白，而没人知道为什么。同 {@code FeedPageResponse.rankMode} 的理由。
 *
 * <p>本版<b>不分页</b>：冷启动只有雅加达 10–20 个场所（PRD ⑤），一次取完比让客户端翻页更快。
 * 要加分页时沿用 {@code FeedPageResponse} 的 {@code {items, nextCursor, hasMore}} 游标形态，
 * <b>不要</b>换成 page/size。
 */
public record PlaceListResponse(
        List<PlaceListItemResponse> items,
        String sortMode) {

    /** 按创建时间倒序（无定位权限 / 未带坐标，FR-112.2）。 */
    public static final String SORT_MODE_RECENT = "recent";

    /** 按直线距离升序（携带坐标，Story 1.2 接上）。 */
    public static final String SORT_MODE_DISTANCE = "distance";

    /** 「按最新」分支。 */
    public static PlaceListResponse recent(List<PlaceListItemResponse> items) {
        return new PlaceListResponse(items, SORT_MODE_RECENT);
    }

    /**
     * 「按距离」分支（Story 1.2）。
     *
     * <p>⚠️ 只有**真的按距离排过**才用这个工厂：坐标合法但粗筛半径内一个场所都没有时，
     * 服务端回落到按最新，那时必须回 {@link #recent} —— `sortMode` 撒谎会让客户端
     * 把一堆没有距离的项按距离序去显示。
     */
    public static PlaceListResponse distance(List<PlaceListItemResponse> items) {
        return new PlaceListResponse(items, SORT_MODE_DISTANCE);
    }
}

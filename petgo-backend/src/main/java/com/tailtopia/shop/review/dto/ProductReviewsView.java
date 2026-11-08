package com.tailtopia.shop.review.dto;

import java.util.List;

/**
 * 商品详情页的评价区（Story 7.3）。
 *
 * <p>🔴 <b>无评价时 {@code total=0} 且 {@code averageRating=null}</b> ——
 * 前端据此渲染空态。⚠️ <b>不伪造或预填评价</b>（FR-106）：
 * 一个刚上架的商品就有五星好评，是最快毁掉评价区可信度的做法。
 *
 * <p>🔴 {@code averageRating} 为 null 而<b>不是 0</b>：0 会被渲染成「零分」。
 *
 * <p>v1.4.2 shop Story 6.1（D-17）：末尾追加 {@code displayEnabled / nextCursor / hasMore}；
 * {@code total / averageRating} 只算已发布且未隐藏，旧版 App 只读这两个。
 */
public record ProductReviewsView(long total, Double averageRating, List<ShopReviewView> items,
        /** v1.4.2 Story 6.1：评价列表展示开关；false（默认）→ {@code items = []}，条数与均分照给。 */
        boolean displayEnabled,
        /** 下一页游标（不透明 base64url）；末页 / 开关关 → null（NON_NULL 下省略）。 */
        String nextCursor,
        boolean hasMore) {

    public static ProductReviewsView empty() {
        return new ProductReviewsView(0, null, List.of(), false, null, false);
    }
}

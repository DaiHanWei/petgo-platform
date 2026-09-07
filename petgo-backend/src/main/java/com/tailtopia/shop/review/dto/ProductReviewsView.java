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
 */
public record ProductReviewsView(long total, Double averageRating, List<ShopReviewView> items) {

    public static ProductReviewsView empty() {
        return new ProductReviewsView(0, null, List.of());
    }
}

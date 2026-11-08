package com.tailtopia.shop.review.repository;

/**
 * 「已发布且未隐藏」评价的条数 + 均分（v1.4.2 shop Story 6.1，{@code ShopReviewRepository.visibleStats} 投影）。
 *
 * @param averageRating 无评价时为 null（🔴 不是 0，0 会被读成「零分」）
 */
public record ShopReviewStats(long total, Double averageRating) {

    /** JPQL 构造表达式入口：{@code COUNT} 回 {@link Long}、{@code AVG} 回 {@link Double}。 */
    public ShopReviewStats(Long total, Double averageRating) {
        this(total == null ? 0L : total.longValue(), averageRating);
    }

    public static ShopReviewStats empty() {
        return new ShopReviewStats(0L, null);
    }
}

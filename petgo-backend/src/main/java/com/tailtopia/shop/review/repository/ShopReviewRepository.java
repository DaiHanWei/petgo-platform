package com.tailtopia.shop.review.repository;

import com.tailtopia.shop.review.domain.ReviewStatus;
import com.tailtopia.shop.review.domain.ShopReview;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 商品评价仓储（Story 7.1）。
 *
 * <p>v1.4.2 shop Story 6.1（D-17）：详情页 / {@code /reviews} 读路径一律只读「已发布且未隐藏」
 * （{@code hidden_at IS NULL}，部分索引 {@code ix_shop_reviews_product_visible}）；
 * 「我的评价」（{@link #findByUserIdOrderByCreatedAtDescIdDesc}）不受隐藏影响。
 */
public interface ShopReviewRepository extends JpaRepository<ShopReview, Long> {

    Optional<ShopReview> findByShopOrderLineId(long shopOrderLineId);

    /** 详情页评价区：🔴 **按时间倒序**，且只出已发布、未隐藏的。 */
    List<ShopReview> findByProductIdAndReviewStatusAndHiddenAtIsNullOrderByCreatedAtDescIdDesc(long productId,
            ReviewStatus status, Pageable pageable);

    long countByProductIdAndReviewStatusAndHiddenAtIsNull(long productId, ReviewStatus status);

    /** 平均星级（只算已发布未隐藏；无则返回 null —— 🔴 不返回 0，那会被读成「零分」）。 */
    @Query("""
            SELECT AVG(r.rating) FROM ShopReview r
            WHERE r.productId = :productId
              AND r.reviewStatus = com.tailtopia.shop.review.domain.ReviewStatus.PUBLISHED
              AND r.hiddenAt IS NULL
            """)
    Double averageRating(@Param("productId") long productId);

    /** 条数 + 均分一条 SQL（v1.4.2 Story 6.1 AC11.3，详情摘要）。无评价 → {@code (0, null)}。 */
    @Query("""
            SELECT new com.tailtopia.shop.review.repository.ShopReviewStats(COUNT(r), AVG(r.rating))
            FROM ShopReview r
            WHERE r.productId = :productId
              AND r.reviewStatus = com.tailtopia.shop.review.domain.ReviewStatus.PUBLISHED
              AND r.hiddenAt IS NULL
            """)
    ShopReviewStats visibleStats(@Param("productId") long productId);

    /**
     * 游标分页（v1.4.2 Story 6.1 AC10）：已发布未隐藏，{@code (created_at, id) < (:ts, :id)}，
     * 按 {@code created_at DESC, id DESC}。调用方取 {@code size + 1} 判 hasMore；首页传哨兵（最大时刻 / Long.MAX）。
     */
    @Query("""
            SELECT r FROM ShopReview r
            WHERE r.productId = :productId
              AND r.reviewStatus = com.tailtopia.shop.review.domain.ReviewStatus.PUBLISHED
              AND r.hiddenAt IS NULL
              AND (r.createdAt < :ts OR (r.createdAt = :ts AND r.id < :id))
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    List<ShopReview> pageVisible(@Param("productId") long productId, @Param("ts") Instant ts,
            @Param("id") long id, Pageable pageable);

    List<ShopReview> findByUserIdOrderByCreatedAtDescIdDesc(long userId);

    /** 三方降级后的待重试队列。 */
    List<ShopReview> findByReviewStatusOrderByCreatedAtAsc(ReviewStatus status, Pageable pageable);
}

package com.tailtopia.shop.review.repository;

import com.tailtopia.shop.review.domain.ReviewStatus;
import com.tailtopia.shop.review.domain.ShopReview;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 商品评价仓储（Story 7.1）。 */
public interface ShopReviewRepository extends JpaRepository<ShopReview, Long> {

    Optional<ShopReview> findByShopOrderLineId(long shopOrderLineId);

    /** 详情页评价区：🔴 **按时间倒序**，且只出已发布的。 */
    List<ShopReview> findByProductIdAndReviewStatusOrderByCreatedAtDescIdDesc(long productId,
            ReviewStatus status, Pageable pageable);

    long countByProductIdAndReviewStatus(long productId, ReviewStatus status);

    /** 平均星级（无已发布评价时返回 null —— 🔴 不返回 0，那会被读成「零分」）。 */
    @Query("""
            SELECT AVG(r.rating) FROM ShopReview r
            WHERE r.productId = :productId
              AND r.reviewStatus = com.tailtopia.shop.review.domain.ReviewStatus.PUBLISHED
            """)
    Double averageRating(@Param("productId") long productId);

    List<ShopReview> findByUserIdOrderByCreatedAtDescIdDesc(long userId);

    /** 三方降级后的待重试队列。 */
    List<ShopReview> findByReviewStatusOrderByCreatedAtAsc(ReviewStatus status, Pageable pageable);
}

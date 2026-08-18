package com.tailtopia.shop.review.domain;

import com.tailtopia.shared.error.AppException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 商品评价（Story 7.1，FR-106）。 */
@Entity
@Table(name = "shop_reviews")
public class ShopReview {

    /** 文字上限。 */
    public static final int MAX_CONTENT = 500;
    /** 图片上限。 */
    public static final int MAX_IMAGES = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_order_line_id", nullable = false, updatable = false)
    private Long shopOrderLineId;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private Long skuId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 🔴 1–5 必填 —— 文字与图都选填，但没有星级的「评价」对下一个买家毫无信息量。 */
    @Column(name = "rating", nullable = false)
    private short rating;

    @Column(name = "content", length = MAX_CONTENT)
    private String content;

    @Column(name = "image_keys", length = 1000)
    private String imageKeys;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 16)
    private ReviewStatus reviewStatus;

    /** 🔒 只记结论分类，<b>不记原文</b>。 */
    @Column(name = "moderation_label", length = 64)
    private String moderationLabel;

    @Column(name = "moderation_score", precision = 4, scale = 3)
    private BigDecimal moderationScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopReview() {
    }

    public static ShopReview of(long orderLineId, long skuId, long productId, long userId,
            int rating, String content, List<String> imageKeys) {
        if (rating < 1 || rating > 5) {
            throw AppException.validation("请给出 1–5 星评分");
        }
        if (content != null && content.length() > MAX_CONTENT) {
            throw AppException.validation("评价文字不能超过 " + MAX_CONTENT + " 字");
        }
        if (imageKeys != null && imageKeys.size() > MAX_IMAGES) {
            throw AppException.validation("评价图片最多 " + MAX_IMAGES + " 张");
        }
        ShopReview r = new ShopReview();
        r.shopOrderLineId = orderLineId;
        r.skuId = skuId;
        r.productId = productId;
        r.userId = userId;
        r.rating = (short) rating;
        r.content = content == null || content.isBlank() ? null : content.trim();
        r.imageKeys = imageKeys == null || imageKeys.isEmpty() ? null
                : String.join(",", imageKeys);
        r.reviewStatus = ReviewStatus.PENDING;
        r.createdAt = Instant.now();
        r.updatedAt = r.createdAt;
        return r;
    }

    /** 审核结论落地。🔒 只记分类与评分，不记原文。 */
    public void applyModeration(ReviewStatus status, String label, Double score) {
        this.reviewStatus = status;
        this.moderationLabel = label;
        this.moderationScore = score == null ? null : BigDecimal.valueOf(score)
                .setScale(3, java.math.RoundingMode.HALF_UP);
        this.updatedAt = Instant.now();
    }

    public boolean isPublished() {
        return reviewStatus == ReviewStatus.PUBLISHED;
    }

    public Long getId() {
        return id;
    }

    public Long getShopOrderLineId() {
        return shopOrderLineId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getUserId() {
        return userId;
    }

    public short getRating() {
        return rating;
    }

    public String getContent() {
        return content;
    }

    public List<String> imageKeyList() {
        return imageKeys == null || imageKeys.isBlank() ? List.of()
                : List.of(imageKeys.split(","));
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public String getModerationLabel() {
        return moderationLabel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 🔒 不打印评价原文 —— 它可能含用户的个人叙述。 */
    @Override
    public String toString() {
        return "ShopReview[" + id + ", " + rating + "star, " + reviewStatus + "]";
    }
}

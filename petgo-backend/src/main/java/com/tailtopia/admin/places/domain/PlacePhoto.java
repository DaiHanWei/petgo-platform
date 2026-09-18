package com.tailtopia.admin.places.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/** 场所照片（Story 5.1；表 {@code place_photos}）。{@link #objectKey} 只存 OSS objectKey（照 {@code SeedBatchAsset.objectKey}），签名 URL 现签不落库。 */
// JPA 实体名与 App 侧 com.tailtopia.place.domain.PlacePhoto 区分（同表两套映射，2026-09-18 场所表对齐）；
// 🔴 JPQL 里要写 AdminPlacePhoto，不是类名。
@Entity(name = "AdminPlacePhoto")
@Table(name = "place_photos")
public class PlacePhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "place_id", nullable = false, updatable = false)
    private Long placeId;

    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    @Column(name = "uploader_user_id", nullable = false, updatable = false)
    private Long uploaderUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // ── 以下四列由 2026-09-18 场所表对齐迁移追加（App 侧先发后审 / 首批图 / 站外预览资格）──
    /** 审核态（VISIBLE / UNDER_REVIEW / REJECTED / …，值域归 App 侧 CommentModerationStatus）。后台只读展示。 */
    @Column(name = "moderation_status", nullable = false, length = 24)
    private String moderationStatus = "VISIBLE";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 首批图（标记时 / 运营录入的那批）：注销隐藏补充照片时不动它们。 */
    @Column(name = "is_original", nullable = false)
    private boolean original;

    /** 能否作站外分享页 og:image（只给干净 PASS 与运营录入）。 */
    @Column(name = "og_eligible", nullable = false)
    private boolean ogEligible;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlacePhoto() {
    }

    public static PlacePhoto create(long placeId, String objectKey, long uploaderUserId) {
        return createByOperator(placeId, objectKey, uploaderUserId, 0);
    }

    /**
     * 运营在后台录入的照片（D6，2026-09-18 拍板）：可信来源 —— 直接可见、算首批图、可作站外预览图。
     * {@code sortOrder} 按上传顺序，App 详情页的照片流按它排。
     */
    public static PlacePhoto createByOperator(long placeId, String objectKey, long uploaderUserId, int sortOrder) {
        PlacePhoto p = new PlacePhoto();
        p.placeId = placeId;
        p.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        p.uploaderUserId = uploaderUserId;
        p.moderationStatus = "VISIBLE";
        p.sortOrder = sortOrder;
        p.original = true;
        p.ogEligible = true;
        return p;
    }

    /** 软删（运营删照片，Story 5.3；计数实时统计，无需加减）。 */
    public boolean softDelete() {
        if (deletedAt != null) {
            return false;
        }
        deletedAt = Instant.now();
        return true;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Long getId() {
        return id;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public String getModerationStatus() {
        return moderationStatus;
    }

    public boolean isOriginal() {
        return original;
    }

    public boolean isOgEligible() {
        return ogEligible;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public Long getUploaderUserId() {
        return uploaderUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}

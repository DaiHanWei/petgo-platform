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
@Entity
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

    protected PlacePhoto() {
    }

    public static PlacePhoto create(long placeId, String objectKey, long uploaderUserId) {
        PlacePhoto p = new PlacePhoto();
        p.placeId = placeId;
        p.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        p.uploaderUserId = uploaderUserId;
        return p;
    }

    /** 软删（运营删照片，Story 5.3；服务层同事务对 {@code places.photo_count} -1）。 */
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

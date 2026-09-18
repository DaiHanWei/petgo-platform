package com.tailtopia.admin.places.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/** 场所评论（Story 5.1；表 {@code place_comments}）：正文 ≤500 + 二元态度；软删。计数由服务层维护到 {@code places}。 */
@Entity
@Table(name = "place_comments")
public class PlaceComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "place_id", nullable = false, updatable = false)
    private Long placeId;

    @Column(name = "author_user_id", nullable = false, updatable = false)
    private Long authorUserId;

    @Column(name = "body", nullable = false, length = 500)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "attitude", nullable = false, length = 16)
    private PlaceAttitude attitude;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected PlaceComment() {
    }

    public static PlaceComment create(long placeId, long authorUserId, String body, PlaceAttitude attitude) {
        PlaceComment c = new PlaceComment();
        c.placeId = placeId;
        c.authorUserId = authorUserId;
        c.body = Objects.requireNonNull(body, "body");
        c.attitude = Objects.requireNonNull(attitude, "attitude");
        return c;
    }

    /** 软删（运营删评论，Story 5.3；服务层同事务对 {@code comment_count} 与对应态度计数 -1）。 */
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

    public Long getAuthorUserId() {
        return authorUserId;
    }

    public String getBody() {
        return body;
    }

    public PlaceAttitude getAttitude() {
        return attitude;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}

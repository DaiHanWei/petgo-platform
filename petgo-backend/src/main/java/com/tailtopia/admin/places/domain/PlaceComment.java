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

/**
 * 场所评论（Story 5.1；表 {@code place_comments}）：正文 ≤500 + 态度（可空）；软删。
 * <p>2026-09-18 场所表对齐：App 的评论态度是可选的（只发文字、不表态）→ {@link #attitude} 可为 null；
 * 计数不再缓存在 {@code places}，后台实时统计。审核态由 App 侧维护，后台只读展示。
 */
// JPA 实体名与 App 侧 com.tailtopia.place.domain.PlaceComment 区分（同表两套映射，2026-09-18 场所表对齐）；
// 🔴 JPQL 里要写 AdminPlaceComment，不是类名。
@Entity(name = "AdminPlaceComment")
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

    /** null = 未表态（App 允许只发文字）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "attitude", length = 16)
    private PlaceAttitude attitude;

    /** 审核态（App 侧先发后审，值域归 App 的 CommentModerationStatus）。后台只读展示，不写。 */
    @Column(name = "moderation_status", nullable = false, length = 24, insertable = false, updatable = false)
    private String moderationStatus;

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
        c.attitude = attitude;
        return c;
    }

    /** 软删（运营删评论，Story 5.3；计数实时统计，无需加减）。 */
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

    public String getModerationStatus() {
        return moderationStatus;
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

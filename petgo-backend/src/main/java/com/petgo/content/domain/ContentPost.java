package com.petgo.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 内容帖子（Story 2.3 创建 {@code content_posts} 表）。三类内容发布的数据根，Epic 3 复用。
 *
 * <p>弹性字段：{@code imageUrls} 映射 JSONB；{@code type}/{@code status}/{@code dangerLevel} 落 varchar。
 * 软删 {@code deletedAt}（为 Epic3 删除/注销级联准备）。时间戳 UTC。
 */
@Entity
@Table(name = "content_posts")
public class ContentPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ContentType type;

    @Column(name = "pet_id")
    private Long petId;

    @Column(name = "text", length = 1000)
    private String text;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_urls")
    private List<String> imageUrls;

    @Column(name = "danger_level", length = 8)
    private String dangerLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PostStatus status = PostStatus.PUBLISHED;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentPost() {
    }

    public static ContentPost publish(long authorId, ContentType type, Long petId, String text,
            List<String> imageUrls) {
        ContentPost p = new ContentPost();
        p.authorId = authorId;
        p.type = type;
        p.petId = petId;
        p.text = text;
        p.imageUrls = imageUrls;
        p.status = PostStatus.PUBLISHED;
        return p;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public ContentType getType() {
        return type;
    }

    public Long getPetId() {
        return petId;
    }

    public String getText() {
        return text;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public String getDangerLevel() {
        return dangerLevel;
    }

    public PostStatus getStatus() {
        return status;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

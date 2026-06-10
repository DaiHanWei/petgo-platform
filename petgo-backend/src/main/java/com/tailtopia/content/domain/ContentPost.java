package com.tailtopia.content.domain;

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
import java.time.LocalDate;
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

    /** 成长日历事件日期（F9）：仅 GROWTH_MOMENT 有值，决定档案侧显示位置；与 createdAt 排序解耦。 */
    @Column(name = "event_date")
    private LocalDate eventDate;

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

    /** 非成长日历发布（无事件日期）。委托至带 eventDate 的规范工厂。 */
    public static ContentPost publish(long authorId, ContentType type, Long petId, String text,
            List<String> imageUrls) {
        return publish(authorId, type, petId, text, imageUrls, null);
    }

    public static ContentPost publish(long authorId, ContentType type, Long petId, String text,
            List<String> imageUrls, LocalDate eventDate) {
        ContentPost p = new ContentPost();
        p.authorId = authorId;
        p.type = type;
        p.petId = petId;
        p.text = text;
        p.imageUrls = imageUrls;
        p.eventDate = eventDate;
        p.status = PostStatus.PUBLISHED;
        return p;
    }

    /** 软删（Story 3.6 作者删除 / 3.7 运营下架 / 7.3 注销级联）。不物理删，保留行结构。 */
    public void softDelete() {
        this.deletedAt = Instant.now();
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

    public LocalDate getEventDate() {
        return eventDate;
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

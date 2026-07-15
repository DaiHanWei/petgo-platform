package com.tailtopia.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 内容评论（Story 3.3 建表 + 只读；Story 3.5 写入）。两级结构：{@code parentId} 为 null 是一级评论，
 * 非空是二级回复（指向某一级评论，**不再嵌套第三级**）。软删 {@code deletedAt}。时间戳 UTC。
 */
@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Comment() {
    }

    /**
     * 新建评论（Story 3.5）。{@code parentId} 为 null = 一级；非空 = 二级（须为一级 id，两级约束在 service 强制）。
     */
    public static Comment create(long postId, Long parentId, long authorId, String body) {
        Comment c = new Comment();
        c.postId = postId;
        c.parentId = parentId;
        c.authorId = authorId;
        c.body = body;
        return c;
    }

    public boolean isTopLevel() {
        return parentId == null;
    }

    /** 软删（Story 3.5 删除 / 级联 / 9.9 admin 下架）。 */
    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    /** 恢复（Story 9.9 admin 误下架恢复）：清软删标记，重回公开口径。 */
    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
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

    public Long getPostId() {
        return postId;
    }

    public Long getParentId() {
        return parentId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public String getBody() {
        return body;
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

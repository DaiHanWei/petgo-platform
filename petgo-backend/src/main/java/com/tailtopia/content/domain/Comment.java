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

    /** 审核可见性态（story 3）。默认 VISIBLE（存量 grandfather、正常 PASS 路径）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 16)
    private CommentModerationStatus moderationStatus = CommentModerationStatus.VISIBLE;

    /** 内容版本键（D-CM3）。body 每次变更 +1，供陈旧审核结果作废。V1 无编辑端点故恒为 1。 */
    @Column(name = "content_version", nullable = false)
    private int contentVersion = 1;

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
        c.moderationStatus = CommentModerationStatus.VISIBLE;
        return c;
    }

    /**
     * 新建挂起评论（story 3，三方降级 fail-closed）。落 {@link CommentModerationStatus#UNDER_REVIEW}——
     * 作者视角照常发出（D-CM2 无标签），他人不可见，等人工队列判定；调用方不发 {@code ContentCommentedEvent}。
     */
    public static Comment createUnderReview(long postId, Long parentId, long authorId, String body) {
        Comment c = new Comment();
        c.postId = postId;
        c.parentId = parentId;
        c.authorId = authorId;
        c.body = body;
        c.moderationStatus = CommentModerationStatus.UNDER_REVIEW;
        return c;
    }

    public boolean isTopLevel() {
        return parentId == null;
    }

    /** 人工审核通过（story 3）：UNDER_REVIEW → VISIBLE（此刻由 service 发新评论事件）。仅挂起态可转。 */
    public void approveModeration() {
        if (moderationStatus == CommentModerationStatus.UNDER_REVIEW) {
            moderationStatus = CommentModerationStatus.VISIBLE;
        }
    }

    /** 人工审核拒绝 / 超时（story 3）：UNDER_REVIEW → REJECTED（终态，仍仅作者可见，永不发新评论事件）。 */
    public void rejectModeration() {
        if (moderationStatus == CommentModerationStatus.UNDER_REVIEW) {
            moderationStatus = CommentModerationStatus.REJECTED;
        }
    }

    /** FR-55A 巡查下架（story 3）：仅 VISIBLE 可下架 → TAKEN_DOWN（仅作者可见 + 标签）。返回是否发生迁移（幂等）。 */
    public boolean takedown() {
        if (moderationStatus == CommentModerationStatus.VISIBLE) {
            moderationStatus = CommentModerationStatus.TAKEN_DOWN;
            return true;
        }
        return false;
    }

    /** FR-55A 恢复（story 3）：TAKEN_DOWN / REJECTED → VISIBLE（不通知、不重发事件）。返回是否发生迁移（幂等）。 */
    public boolean restoreModeration() {
        if (moderationStatus == CommentModerationStatus.TAKEN_DOWN
                || moderationStatus == CommentModerationStatus.REJECTED) {
            moderationStatus = CommentModerationStatus.VISIBLE;
            return true;
        }
        return false;
    }

    /** 软删（Story 3.5 删除 / 级联）。 */
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

    public CommentModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public int getContentVersion() {
        return contentVersion;
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

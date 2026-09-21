package com.tailtopia.admin.warmreply.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 暖贴回复跟进项 {@code warm_reply_followups}（V1.3.0 Story 4.3，AD-6）：虚拟账号的一级评论收到真实用户回复即入队，
 * 多条回复合并累加（部分唯一索引 {@code UNIQUE(virtual_comment_id) WHERE status='PENDING'}）；回复被删 / 下架减计数，
 * 归零即删行（D-19）；处理后 HANDLED 保留可回看。入队 upsert 走原生 SQL（{@code WarmReplyQueueService}），实体只承担读与处理态迁移。
 */
@Entity
@Table(name = "warm_reply_followups")
public class WarmReplyFollowup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "virtual_comment_id", nullable = false)
    private Long virtualCommentId;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "virtual_user_id", nullable = false)
    private Long virtualUserId;

    @Column(name = "pending_reply_count", nullable = false)
    private int pendingReplyCount = 1;

    @Column(name = "last_reply_id")
    private Long lastReplyId;

    @Column(name = "last_reply_at")
    private Instant lastReplyAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FollowupStatus status = FollowupStatus.PENDING;

    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "handled_action", length = 16)
    private HandledAction handledAction;

    @Column(name = "handled_comment_id")
    private Long handledCommentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected WarmReplyFollowup() {
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** 标记已处理（4-4：REPLIED 带运营以虚拟身份发出的回复 id；READ 只读不回）。已处理项幂等 no-op。 */
    public boolean markHandled(long adminAccountId, HandledAction action, Long handledCommentId) {
        if (status != FollowupStatus.PENDING) {
            return false;
        }
        this.status = FollowupStatus.HANDLED;
        this.handledBy = adminAccountId;
        this.handledAt = Instant.now();
        this.handledAction = action;
        this.handledCommentId = handledCommentId;
        return true;
    }

    public Long getId() {
        return id;
    }

    public Long getVirtualCommentId() {
        return virtualCommentId;
    }

    public Long getPostId() {
        return postId;
    }

    public Long getVirtualUserId() {
        return virtualUserId;
    }

    public int getPendingReplyCount() {
        return pendingReplyCount;
    }

    public Long getLastReplyId() {
        return lastReplyId;
    }

    public Instant getLastReplyAt() {
        return lastReplyAt;
    }

    public FollowupStatus getStatus() {
        return status;
    }

    public Long getHandledBy() {
        return handledBy;
    }

    public Instant getHandledAt() {
        return handledAt;
    }

    public HandledAction getHandledAction() {
        return handledAction;
    }

    public Long getHandledCommentId() {
        return handledCommentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

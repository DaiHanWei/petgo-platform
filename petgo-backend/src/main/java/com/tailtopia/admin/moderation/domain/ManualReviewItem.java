package com.tailtopia.admin.moderation.domain;

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
 * 人工审核队列项（Story 4.3，AB-3C）。未过自动审核且开关激活时一条内容入队挂起（PENDING），
 * 运营通过/拒绝或超 3 天自动 TIMED_OUT。状态机幂等——仅 PENDING 可被处置/超时扫描。
 */
@Entity
@Table(name = "manual_review_queue")
public class ManualReviewItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    /** 多态：帖子 / 评论条目（story 3，存量 grandfather 到 CONTENT_POST）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 16)
    private ReviewContentType contentType = ReviewContentType.CONTENT_POST;

    /** 入队时捕获的内容版本（D-CM3 陈旧作废；帖子/评论通用，可空）。 */
    @Column(name = "content_version")
    private Integer contentVersion;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReviewStatus status = ReviewStatus.PENDING;

    /** 处置人 admin_accounts.id（PENDING 时空）。 */
    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ManualReviewItem() {
    }

    /** 新建帖子挂起项（PENDING，{@code content_type=CONTENT_POST}）。 */
    public static ManualReviewItem pending(long contentId, Instant submittedAt) {
        ManualReviewItem it = new ManualReviewItem();
        it.contentId = contentId;
        it.contentType = ReviewContentType.CONTENT_POST;
        it.submittedAt = submittedAt;
        it.status = ReviewStatus.PENDING;
        return it;
    }

    /** 新建评论挂起项（PENDING，{@code content_type=COMMENT}，捕获入队版本；story 3）。 */
    public static ManualReviewItem pendingComment(long commentId, int contentVersion, Instant submittedAt) {
        ManualReviewItem it = new ManualReviewItem();
        it.contentId = commentId;
        it.contentType = ReviewContentType.COMMENT;
        it.contentVersion = contentVersion;
        it.submittedAt = submittedAt;
        it.status = ReviewStatus.PENDING;
        return it;
    }

    /** 处置为终态（APPROVED/REJECTED/TIMED_OUT）并记处置人/时间。 */
    public void decide(ReviewStatus terminal, Long decidedBy, Instant decidedAt) {
        this.status = terminal;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
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

    public Long getContentId() {
        return contentId;
    }

    public ReviewContentType getContentType() {
        return contentType;
    }

    public Integer getContentVersion() {
        return contentVersion;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}

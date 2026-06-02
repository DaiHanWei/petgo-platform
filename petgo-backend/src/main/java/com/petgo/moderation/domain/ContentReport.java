package com.petgo.moderation.domain;

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
 * 内容举报工单（Story 3.7，FR-25）。进运营人工审核队列，**无自动下架**；状态由 ADMIN 流转。
 * 同 reporter 对同 post 唯一（{@code uq_content_reports_reporter_post}），重复举报 service 幂等。
 */
@Entity
@Table(name = "content_reports")
public class ContentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_type", nullable = false, length = 16)
    private ReportReason reasonType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReportStatus status = ReportStatus.PENDING;

    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentReport() {
    }

    public static ContentReport create(long postId, long reporterId, ReportReason reasonType) {
        ContentReport r = new ContentReport();
        r.postId = postId;
        r.reporterId = reporterId;
        r.reasonType = reasonType;
        r.status = ReportStatus.PENDING;
        return r;
    }

    /** ADMIN 人工处理：下架(RESOLVED)/驳回(DISMISSED)，记处理人与时刻。 */
    public void resolveBy(long adminId, ReportStatus decision) {
        this.status = decision;
        this.handledBy = adminId;
        this.handledAt = Instant.now();
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

    public Long getReporterId() {
        return reporterId;
    }

    public ReportReason getReasonType() {
        return reasonType;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public Long getHandledBy() {
        return handledBy;
    }

    public Instant getHandledAt() {
        return handledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

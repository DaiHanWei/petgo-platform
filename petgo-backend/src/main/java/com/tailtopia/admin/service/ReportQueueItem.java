package com.tailtopia.admin.service;

import java.time.Instant;

/**
 * Admin 举报队列视图项（Story 3.7；4.1 加 reporterId/authorId/handledBy/handledAt）。Thymeleaf getter 访问。
 */
public class ReportQueueItem {

    private final long reportId;
    private final long postId;
    private final Long reporterId;
    private final Long authorId;
    private final String reasonType;
    private final String status;
    private final Instant createdAt;
    private final long reportCount;
    private final String postTextPreview;
    private final boolean postDeleted;
    private final Long handledBy;
    private final Instant handledAt;

    public ReportQueueItem(long reportId, long postId, Long reporterId, Long authorId, String reasonType,
            String status, Instant createdAt, long reportCount, String postTextPreview,
            boolean postDeleted, Long handledBy, Instant handledAt) {
        this.reportId = reportId;
        this.postId = postId;
        this.reporterId = reporterId;
        this.authorId = authorId;
        this.reasonType = reasonType;
        this.status = status;
        this.createdAt = createdAt;
        this.reportCount = reportCount;
        this.postTextPreview = postTextPreview;
        this.postDeleted = postDeleted;
        this.handledBy = handledBy;
        this.handledAt = handledAt;
    }

    public long getReportId() {
        return reportId;
    }

    public long getPostId() {
        return postId;
    }

    public Long getReporterId() {
        return reporterId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public String getReasonType() {
        return reasonType;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getReportCount() {
        return reportCount;
    }

    public String getPostTextPreview() {
        return postTextPreview;
    }

    public boolean isPostDeleted() {
        return postDeleted;
    }

    public Long getHandledBy() {
        return handledBy;
    }

    public Instant getHandledAt() {
        return handledAt;
    }
}

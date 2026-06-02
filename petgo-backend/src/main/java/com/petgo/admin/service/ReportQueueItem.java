package com.petgo.admin.service;

import java.time.Instant;

/**
 * Admin 举报队列视图项（Story 3.7）。Thymeleaf getter 访问。聚合工单 + 被举报内容快照 + 举报次数。
 */
public class ReportQueueItem {

    private final long reportId;
    private final long postId;
    private final String reasonType;
    private final String status;
    private final Instant createdAt;
    private final long reportCount;
    private final String postTextPreview;
    private final boolean postDeleted;

    public ReportQueueItem(long reportId, long postId, String reasonType, String status,
            Instant createdAt, long reportCount, String postTextPreview, boolean postDeleted) {
        this.reportId = reportId;
        this.postId = postId;
        this.reasonType = reasonType;
        this.status = status;
        this.createdAt = createdAt;
        this.reportCount = reportCount;
        this.postTextPreview = postTextPreview;
        this.postDeleted = postDeleted;
    }

    public long getReportId() {
        return reportId;
    }

    public long getPostId() {
        return postId;
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
}

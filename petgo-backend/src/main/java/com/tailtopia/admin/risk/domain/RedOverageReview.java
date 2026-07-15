package com.tailtopia.admin.risk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 红色超额人工复核标记（Story 9.6，AB-7A）。user 维度单行（{@code user_id} 主键）。**纯注记**——
 * 绝不驱动任何自动拦截/限流/封禁。RED 计数实时聚合自 triage_tasks，不落本表。
 */
@Entity
@Table(name = "red_overage_reviews")
public class RedOverageReview {

    public static final String TO_VERIFY = "TO_VERIFY";
    public static final String RESOLVED = "RESOLVED";

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "reviewed_by", nullable = false)
    private Long reviewedBy;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RedOverageReview() {
    }

    public static RedOverageReview of(long userId, String status, String note, long adminId) {
        RedOverageReview r = new RedOverageReview();
        r.userId = userId;
        r.apply(status, note, adminId);
        r.updatedAt = Instant.now();
        return r;
    }

    public void apply(String status, String note, long adminId) {
        this.status = status;
        this.note = note;
        this.reviewedBy = adminId;
        this.reviewedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}

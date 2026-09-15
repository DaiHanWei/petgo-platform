package com.tailtopia.place.domain;

import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.moderation.domain.ReportStatus;
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
 * 场所举报工单（V1.3.0 batch-b1 Story 1.5 · AC5）。
 *
 * <p>🔴 <b>独立建表，不改既有 {@code content_reports}、不用多态外键</b> —— 判据与 AD-8 对场所评论的
 * 完全一致：{@code content_reports.post_id} 是 NOT NULL 且语义绑死内容帖，共表就得把它改成可空
 * 再加场所列，那正是 v1.1.6 AD-10 已经否过的多态外键。
 *
 * <p><b>「复用五类选项」复用的是取值域与抽屉文案</b>（AC5 要求一字不改）——
 * 所以这里直接 import {@link ReportReason} / {@link ReportStatus}，而不是另抄一份枚举。
 * 另抄一份就是第二份清单，两份清单迟早对不上。
 *
 * <p><b>无自动下架</b>（同 Story 3.7 / FR-25）：写工单 PENDING 进运营队列，处置在后台 AB-17A。
 */
@Entity
@Table(name = "place_reports")
public class PlaceReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "place_id", nullable = false, updatable = false)
    private Long placeId;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_type", nullable = false, length = 16)
    private ReportReason reasonType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReportStatus status = ReportStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlaceReport() {
    }

    public static PlaceReport of(long placeId, long reporterId, ReportReason reason) {
        PlaceReport r = new PlaceReport();
        r.placeId = placeId;
        r.reporterId = reporterId;
        r.reasonType = reason;
        r.status = ReportStatus.PENDING;
        return r;
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

    public Long getPlaceId() {
        return placeId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

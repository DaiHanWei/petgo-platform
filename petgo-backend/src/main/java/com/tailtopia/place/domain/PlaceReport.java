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

    /** 列名随后台 schema（2026-09-18 场所表对齐）。 */
    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_type", nullable = false, length = 24, updatable = false)
    private ReportReason reasonType;

    /**
     * 处置状态。🔴 值域归后台（PENDING / DISMISSED / ACTIONED，{@code ck_place_reports_status}），
     * 与全站的 {@link ReportStatus} 不同（那边没有 ACTIONED）—— 所以这里存字符串、App 只写 PENDING、从不改：
     * 映射成 ReportStatus 的话，App 一旦读到运营处理过的行，枚举解析就会直接抛。
     */
    @Column(name = "status", nullable = false, length = 16, updatable = false)
    private String status = ReportStatus.PENDING.name();

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
        r.status = ReportStatus.PENDING.name();
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

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

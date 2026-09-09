package com.tailtopia.admin.places.domain;

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
import java.util.Objects;

/**
 * 场所举报（Story 5.1，D-5 独立建表；表 {@code place_reports}）。同 reporter 对同 place 唯一（{@code uq_place_reports_reporter_place}），重复举报幂等。
 * 处置流在 Story 5.4 接进 A1 统一队列；{@link #handleBy} 照 {@code ContentReport.resolveBy}。{@link #handledBy} 是后台账号 id（不加 FK）。
 */
@Entity
@Table(name = "place_reports")
public class PlaceReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "place_id", nullable = false, updatable = false)
    private Long placeId;

    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private Long reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_type", nullable = false, length = 24)
    private PlaceReportReason reasonType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PlaceReportStatus status = PlaceReportStatus.PENDING;

    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlaceReport() {
    }

    public static PlaceReport create(long placeId, long reporterUserId, PlaceReportReason reasonType) {
        PlaceReport r = new PlaceReport();
        r.placeId = placeId;
        r.reporterUserId = reporterUserId;
        r.reasonType = Objects.requireNonNull(reasonType, "reasonType");
        r.status = PlaceReportStatus.PENDING;
        return r;
    }

    /** 运营处置：驳回（DISMISSED）/ 已处置（ACTIONED），记处理人（后台账号 id）与时刻。PENDING 以外不可再处置。 */
    public void handleBy(long adminAccountId, PlaceReportStatus decision) {
        if (decision == null || decision == PlaceReportStatus.PENDING) {
            throw new IllegalArgumentException("decision must be DISMISSED or ACTIONED");
        }
        if (status != PlaceReportStatus.PENDING) {
            throw new IllegalStateException("report already handled");
        }
        this.status = decision;
        this.handledBy = adminAccountId;
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

    public Long getPlaceId() {
        return placeId;
    }

    public Long getReporterUserId() {
        return reporterUserId;
    }

    public PlaceReportReason getReasonType() {
        return reasonType;
    }

    public PlaceReportStatus getStatus() {
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.tailtopia.admin.failedrequest.domain;

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
 * 问诊请求未成功记录（Story 2.9，AB-2G）。**从未建立会话**即失败的请求（取消/超时/系统故障），
 * 独立于 Epic 5「已建立会话」异常工单。对外用不可枚举 {@code requestToken}，不外露自增 id。
 *
 * <p>{@code SYSTEM_FAILURE} 须强制跟进（未 {@code followedUp} 不可归档）。
 */
@Entity
@Table(name = "failed_consult_requests")
public class FailedConsultRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_token", nullable = false, unique = true, length = 64)
    private String requestToken;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 内部关联的会话 id（不外露；仅排查用）。 */
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "cancelled_at", nullable = false)
    private Instant cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", nullable = false, length = 24)
    private CancelReason cancelReason;

    @Column(name = "online_vet_count", nullable = false)
    private int onlineVetCount;

    @Column(name = "followed_up", nullable = false)
    private boolean followedUp;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FailedConsultRequest() {
    }

    public static FailedConsultRequest of(String requestToken, long userId, Long sessionId,
            Instant submittedAt, Instant cancelledAt, CancelReason reason, int onlineVetCount) {
        FailedConsultRequest r = new FailedConsultRequest();
        r.requestToken = requestToken;
        r.userId = userId;
        r.sessionId = sessionId;
        r.submittedAt = submittedAt;
        r.cancelledAt = cancelledAt;
        r.cancelReason = reason;
        r.onlineVetCount = onlineVetCount;
        r.followedUp = false;
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

    public void setNote(String note) {
        this.note = note;
    }

    public void markFollowedUp() {
        this.followedUp = true;
    }

    /** 归档：SYSTEM_FAILURE 须先跟进，否则拒绝（AC3/AC4）。 */
    public void archive() {
        if (this.cancelReason == CancelReason.SYSTEM_FAILURE && !this.followedUp) {
            throw com.tailtopia.shared.error.AppException.validation("系统故障类需先标记跟进才能归档");
        }
        this.archivedAt = Instant.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public boolean isSystemFailure() {
        return cancelReason == CancelReason.SYSTEM_FAILURE;
    }

    public Long getId() {
        return id;
    }

    public String getRequestToken() {
        return requestToken;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public CancelReason getCancelReason() {
        return cancelReason;
    }

    public int getOnlineVetCount() {
        return onlineVetCount;
    }

    public boolean isFollowedUp() {
        return followedUp;
    }

    public String getNote() {
        return note;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}

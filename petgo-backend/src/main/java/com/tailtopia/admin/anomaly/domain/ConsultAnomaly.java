package com.tailtopia.admin.anomaly.domain;

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
 * 问诊异常工单（Story 5.1，AB-4A）。**仅承载 TailTopia 系统内会话元数据**——
 * 绝不含 IM 正文 / AI 上下文 / 用户媒体（NFR5 红线）。一会话至多一工单（session_id 唯一索引兜底）。
 * 工单不可删（AC6），归档＝置 {@link AnomalyStatus#RESOLVED}。
 */
@Entity
@Table(name = "consult_anomalies")
public class ConsultAnomaly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true)
    private Long sessionId;

    /** 注销匿名化（D1）后会话 user_id 可被置空，故可空。 */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "vet_id", nullable = false)
    private Long vetId;

    @Column(name = "session_started_at")
    private Instant sessionStartedAt;

    @Column(name = "session_ended_at")
    private Instant sessionEndedAt;

    @Column(name = "session_status", nullable = false, length = 16)
    private String sessionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "anomaly_type", nullable = false, length = 32)
    private AnomalyType anomalyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AnomalyStatus status = AnomalyStatus.OPEN;

    /** 内部备注（用户不可见）。 */
    @Column(name = "internal_note")
    private String internalNote;

    /** OSS 私密桶对象 key（绝不存签名 URL）。 */
    @Column(name = "resolution_image_key", length = 255)
    private String resolutionImageKey;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConsultAnomaly() {
    }

    /** 由 {@code ConsultAnomalyRaisedEvent} 元数据建工单（OPEN）。 */
    public static ConsultAnomaly open(long sessionId, long userId, long vetId, Instant startedAt,
            Instant endedAt, String sessionStatus, AnomalyType type) {
        ConsultAnomaly a = new ConsultAnomaly();
        a.sessionId = sessionId;
        a.userId = userId;
        a.vetId = vetId;
        a.sessionStartedAt = startedAt;
        a.sessionEndedAt = endedAt;
        a.sessionStatus = sessionStatus;
        a.anomalyType = type;
        a.status = AnomalyStatus.OPEN;
        return a;
    }

    /** 加内部备注（覆盖式；用户不可见）。 */
    public void setInternalNote(String note) {
        this.internalNote = note;
    }

    /** 标记已处理（归档）：记处置人/时间 + 选填处理图对象 key。 */
    public void resolve(long resolvedBy, Instant resolvedAt, String resolutionImageKey) {
        this.status = AnomalyStatus.RESOLVED;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        if (resolutionImageKey != null && !resolutionImageKey.isBlank()) {
            this.resolutionImageKey = resolutionImageKey;
        }
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

    public Long getSessionId() {
        return sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getVetId() {
        return vetId;
    }

    public Instant getSessionStartedAt() {
        return sessionStartedAt;
    }

    public Instant getSessionEndedAt() {
        return sessionEndedAt;
    }

    public String getSessionStatus() {
        return sessionStatus;
    }

    public AnomalyType getAnomalyType() {
        return anomalyType;
    }

    public AnomalyStatus getStatus() {
        return status;
    }

    public String getInternalNote() {
        return internalNote;
    }

    public String getResolutionImageKey() {
        return resolutionImageKey;
    }

    public Long getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

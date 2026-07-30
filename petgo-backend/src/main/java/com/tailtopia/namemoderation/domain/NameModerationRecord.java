package com.tailtopia.namemoderation.domain;

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
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 名称审核记录（内容审核 story 4，建 {@code name_moderation_records} 表）。名称侧独立状态机 + 人工队列
 * （不复用帖子 {@code manual_review_queue}，理由见 spec §5.1）。
 *
 * <p>命名映射链：列 snake_case ↔ 字段 camelCase。时间戳 UTC。{@code targetRefId} 为内部外键值绝不外露；
 * {@code submittedValue} 为审核证据（可能含 PII），只存本列、严禁写入业务日志。
 */
@Entity
@Table(name = "name_moderation_records")
public class NameModerationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private NameTargetType targetType;

    @Column(name = "target_ref_id", nullable = false)
    private Long targetRefId;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "submitted_value", nullable = false)
    private String submittedValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private NameModerationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 8)
    private NamePriority priority = NamePriority.NORMAL;

    /** 三方评分 0.000–1.000；降级/未评分为 null。numeric(4,3)。 */
    @Column(name = "risk_score", precision = 4, scale = 3)
    private BigDecimal riskScore;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason", length = 64)
    private String decisionReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NameModerationRecord() {
    }

    /** 新建评分中记录（{@code SCORING}，绑定 revision + 送审证据）。 */
    public static NameModerationRecord scoring(NameTargetType targetType, long targetRefId,
            long revision, String submittedValue, Instant submittedAt) {
        NameModerationRecord r = new NameModerationRecord();
        r.targetType = targetType;
        r.targetRefId = targetRefId;
        r.revision = revision;
        r.submittedValue = submittedValue;
        r.status = NameModerationStatus.SCORING;
        r.priority = NamePriority.NORMAL;
        r.submittedAt = submittedAt;
        return r;
    }

    /** 评分完成路由为终态/入队（AUTO_PASSED 或 MANUAL_PENDING）。riskScore 可空（降级）。 */
    public void applyScore(NameModerationStatus target, NamePriority priority, BigDecimal riskScore,
            int retryCount) {
        this.status = target;
        this.priority = priority;
        this.riskScore = riskScore;
        this.retryCount = retryCount;
    }

    /** 被新提交取代（陈旧作废）。 */
    public void supersede() {
        this.status = NameModerationStatus.SUPERSEDED;
    }

    /** 运营处置为终态（RESOLVED_PASS / RESOLVED_VIOLATION），记处置人/时间/类别。 */
    public void resolve(NameModerationStatus terminal, Long decidedBy, Instant decidedAt, String reason) {
        this.status = terminal;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionReason = reason;
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

    public NameTargetType getTargetType() {
        return targetType;
    }

    public Long getTargetRefId() {
        return targetRefId;
    }

    public long getRevision() {
        return revision;
    }

    public String getSubmittedValue() {
        return submittedValue;
    }

    public NameModerationStatus getStatus() {
        return status;
    }

    public NamePriority getPriority() {
        return priority;
    }

    public BigDecimal getRiskScore() {
        return riskScore;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

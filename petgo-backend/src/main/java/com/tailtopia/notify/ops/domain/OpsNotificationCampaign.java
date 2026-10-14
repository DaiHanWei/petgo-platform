package com.tailtopia.notify.ops.domain;

import com.tailtopia.ops.target.OpsTargetType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 运营系统通知发送活动（V1.3.4 FR-138 · AD-17 · D-16 / D-20 / D-24 · Story 6.1）。
 *
 * <p>本主题只交付数据面与服务方法；后台发送页（AB-31）在 admin 主题（D-2）。
 * 🔴 对外标识一律 {@code publicToken}（{@code com.tailtopia.ops.OpsTokenGenerator}，22 位 base62），
 * 埋点 {@code campaign_id} 与 {@code notifications.target_ref} 都用它（D-24），自增 id 不外露。
 * 跳转目标复用 Story 5.1 的 {@link OpsTargetType}，校验唯一口径 = {@code com.tailtopia.ops.target.OpsTargetPolicy}；
 * 库层 CHECK 兜底 {@code (target_type = 'NONE') = (target_value IS NULL)}。
 *
 * <p>{@code audienceSpec} 为 {@link AudienceSpec#toJson()} 产出的 JSON 原文；IDS 模式的用户 id
 * 存子表 {@code ops_notification_campaign_targets}（见 {@code OpsCampaignTargetDao}）。
 */
@Entity
@Table(name = "ops_notification_campaigns")
public class OpsNotificationCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, length = 32, unique = true, updatable = false)
    private String publicToken;

    @Column(name = "title_id", nullable = false, length = 120)
    private String titleId;

    @Column(name = "title_en", nullable = false, length = 120)
    private String titleEn;

    @Column(name = "body_id", nullable = false, length = 255)
    private String bodyId;

    @Column(name = "body_en", nullable = false, length = 255)
    private String bodyEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private OpsTargetType targetType;

    @Column(name = "target_value", length = 512)
    private String targetValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_spec", nullable = false)
    private String audienceSpec;

    /** null = 立即发送；非空 = 到点由定时扫描发送（Story 6.2）。 */
    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OpsCampaignStatus status;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    /** 后台账号 id（admin_accounts.id），无外键。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "started_at")
    private Instant startedAt;

    /** 发送线程每推进一步刷新；SENDING 且超过 15 分钟未刷新 → PARTIAL_FAILED（D-20）。 */
    @Column(name = "last_progress_at")
    private Instant lastProgressAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OpsNotificationCampaign() {
    }

    /**
     * 新建活动（{@code PENDING}，计数为 0）。目标合法性由调用方先经 {@code OpsTargetPolicy} 校验；
     * 人群规格由调用方先经 {@link AudienceSpec#parse} 校验后传 {@link AudienceSpec#toJson()}。
     * {@code NONE} 时 {@code targetValue} 强制为 null（与 CHECK 同口径）。
     */
    public static OpsNotificationCampaign create(String publicToken, String titleId, String titleEn,
            String bodyId, String bodyEn, OpsTargetType targetType, String targetValue,
            String audienceSpecJson, Instant scheduledAt, Long createdBy) {
        OpsNotificationCampaign c = new OpsNotificationCampaign();
        c.publicToken = publicToken;
        c.titleId = titleId;
        c.titleEn = titleEn;
        c.bodyId = bodyId;
        c.bodyEn = bodyEn;
        c.targetType = targetType == null ? OpsTargetType.NONE : targetType;
        c.targetValue = c.targetType == OpsTargetType.NONE ? null : targetValue;
        c.audienceSpec = audienceSpecJson;
        c.scheduledAt = scheduledAt;
        c.status = OpsCampaignStatus.PENDING;
        c.createdBy = createdBy;
        return c;
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

    public String getPublicToken() {
        return publicToken;
    }

    public String getTitleId() {
        return titleId;
    }

    public String getTitleEn() {
        return titleEn;
    }

    public String getBodyId() {
        return bodyId;
    }

    public String getBodyEn() {
        return bodyEn;
    }

    public OpsTargetType getTargetType() {
        return targetType;
    }

    public String getTargetValue() {
        return targetValue;
    }

    public String getAudienceSpec() {
        return audienceSpec;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public OpsCampaignStatus getStatus() {
        return status;
    }

    public int getRecipientCount() {
        return recipientCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastProgressAt() {
        return lastProgressAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCanceledAt() {
        return canceledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

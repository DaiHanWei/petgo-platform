package com.tailtopia.admin.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 操作审计日志（Story 1.3，AM-C1 / A6）。**append-only 不可篡改**：行间 SHA-256 哈希链
 * （{@code prevHash → rowHash}），DB 触发器（V32）拒绝任何 UPDATE/DELETE，仓库窄接口不暴露删改。
 *
 * <p>是 Epic 2~6 所有后台写操作的横切副作用——统一经 {@code AdminAuditService.record(...)} 落库，
 * **各控制器/服务禁止自拼 SQL 或绕过**（架构 Enforcement）。
 *
 * <p>命名映射链：列 snake_case ↔ 字段 camelCase；{@code actionType} 落库 varchar UPPER_SNAKE 过去式；
 * {@code createdAt} 一律 UTC 且在应用侧 {@code truncatedTo(MICROS)}（与 Postgres timestamptz 精度对齐，
 * 否则回读重算哈希对不上）。{@code prevHash}/{@code rowHash} 为 64 位 16 进制摘要（VARCHAR(64)，非 CHAR）。
 * 实体无 setter / 无 {@code @PreUpdate}：构造即定值，落库后永不修改。
 */
@Entity
@Table(name = "admin_audit_logs")
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人后台账号 id（{@code admin_accounts.id}）；系统发起的审计可为 null。 */
    @Column(name = "actor_account_id")
    private Long actorAccountId;

    @Column(name = "action_type", nullable = false, length = 64)
    private String actionType;

    @Column(name = "target_type", length = 64)
    private String targetType;

    /** 目标外露标识：不可枚举 token 或业务 id 字符串（不直接外露自增 id）。 */
    @Column(name = "target_id", length = 128)
    private String targetId;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;

    @Column(name = "row_hash", nullable = false, length = 64)
    private String rowHash;

    protected AdminAuditLog() {
    }

    private AdminAuditLog(Long actorAccountId, String actionType, String targetType, String targetId,
            String summary, Instant createdAt, String prevHash, String rowHash) {
        this.actorAccountId = actorAccountId;
        this.actionType = actionType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.summary = summary;
        this.createdAt = createdAt;
        this.prevHash = prevHash;
        this.rowHash = rowHash;
    }

    /**
     * 构造一条待落库审计行。{@code createdAt} 必须已 {@code truncatedTo(MICROS)}，且 {@code rowHash}
     * 必须由 {@link AuditHashing} 基于完全相同的字段值算出——构造与哈希同源，保证链可独立复算校验。
     */
    public static AdminAuditLog create(Long actorAccountId, String actionType, String targetType,
            String targetId, String summary, Instant createdAt, String prevHash, String rowHash) {
        return new AdminAuditLog(actorAccountId, actionType, targetType, targetId, summary,
                createdAt, prevHash, rowHash);
    }

    public Long getId() {
        return id;
    }

    public Long getActorAccountId() {
        return actorAccountId;
    }

    public String getActionType() {
        return actionType;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getRowHash() {
        return rowHash;
    }
}

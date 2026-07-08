package com.tailtopia.moderation.violation.domain;

import com.tailtopia.admin.moderation.read.ViolationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 账号维度违规计数聚合行（内容审核 story 9，表 {@code violation_counts}）。每个 {@code (account_id, violation_type)}
 * 一行，累计<b>人工判定</b>违规次数。<b>仅记录、不处置</b>——不进任何审核/发布/风控判定链（§3 非目标）。
 *
 * <p>写入走 {@code ViolationCountService.record} 的 PostgreSQL 原子 UPSERT（native），本实体主要供读路径
 * （{@code ViolationCountReader} 展示 + 注销级联删除）。命名映射链：列 snake_case ↔ 字段 camelCase；时间 UTC。
 * {@code account_id} 无 FK（注销级联次序，§5.5）。{@link ViolationType} 复用 cm-8 定义（POST/COMMENT/NAME/AVATAR）。
 */
@Entity
@Table(name = "violation_counts")
public class ViolationCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "violation_type", nullable = false, length = 16)
    private ViolationType violationType;

    @Column(name = "violation_count", nullable = false)
    private int violationCount;

    @Column(name = "first_violation_at")
    private Instant firstViolationAt;

    @Column(name = "last_violation_at")
    private Instant lastViolationAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ViolationCount() {
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public ViolationType getViolationType() {
        return violationType;
    }

    public int getViolationCount() {
        return violationCount;
    }

    public Instant getFirstViolationAt() {
        return firstViolationAt;
    }

    public Instant getLastViolationAt() {
        return lastViolationAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

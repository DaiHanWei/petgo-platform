package com.tailtopia.moderation.throttle.domain;

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
 * 限流（降权）记录（V1.1.6 Story 17.1）。一行 = 对某条内容或某个账号执行过的一次限流。
 *
 * <h2>🛡 AC2：这是降权，不是下架</h2>
 * 本类<b>不持有、也不引用 {@code ContentPost}</b>：限流只往这张表写一行，
 * 从不改内容的 {@code status} / {@code visibility} / {@code deleted_at}。
 * 被限流的内容仍是 {@code PUBLISHED}，直链、作者主页、话题聚合页照常可访问。
 * 🔴 审核相关的既有路径都是「改状态」，顺手复用就会把降权做成下架 ——
 * 这里靠**结构上拿不到内容实体**来杜绝，而不是靠写代码时记得别碰。
 *
 * <h2>🛡 AC3：对用户不可见、不通知</h2>
 * 本表不与 {@code notifications} 产生任何关联，也没有字段供用户侧接口读取。
 * ⚠️ 与「警告」处置不同 —— 警告是要告知的，<b>别复用它的通知路径</b>。
 *
 * <h2>⚠️ AC4：到期自动解除由查询条件构成，没有定时任务</h2>
 * 生效 = {@code liftedAt == null && (expiresAt == null || expiresAt > now)}，
 * 见 {@link #isActiveAt(Instant)}。这样「解除后立即回 1.0、不残留」是**结构上**成立的；
 * 换成扫描器反而会留下「已到期但还没被扫到」这段残留窗口，正是那条 AC 要防的。
 */
@Entity
@Table(name = "rank_throttles")
public class RankThrottle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private ThrottleScope scope;

    /** scope=POST 时是内容 id；scope=ACCOUNT 时是用户 id。 */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "duration", nullable = false, length = 16)
    private ThrottleDuration duration;

    /** 永久限流为 null。 */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    @Column(name = "lifted_by")
    private Long liftedBy;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RankThrottle() {
    }

    public static RankThrottle create(ThrottleScope scope, long targetId,
            ThrottleDuration duration, Instant now, Long operatorId, Long reportId,
            String reason) {
        RankThrottle t = new RankThrottle();
        t.scope = scope;
        t.targetId = targetId;
        t.duration = duration;
        t.expiresAt = duration.expiresFrom(now);
        t.operatorId = operatorId;
        t.reportId = reportId;
        t.reason = reason;
        return t;
    }

    /**
     * 生效判定 —— **唯一实现**。已解除或已到期都不生效。
     *
     * <p>🛡 想知道「这条还在限流吗」的地方一律调这里，不要在各处重写这三个条件的组合：
     * 写漏 {@code liftedAt} 那一半，手动解除就不生效了，而那不会报错。
     */
    public boolean isActiveAt(Instant now) {
        return liftedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** 手动提前解除（AC4）。已解除的再调一次是无操作，不覆盖首次解除的时刻与操作人。 */
    public void lift(Instant now, long adminId) {
        if (liftedAt != null) {
            return;
        }
        this.liftedAt = now;
        this.liftedBy = adminId;
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

    public ThrottleScope getScope() {
        return scope;
    }

    public Long getTargetId() {
        return targetId;
    }

    public ThrottleDuration getDuration() {
        return duration;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLiftedAt() {
        return liftedAt;
    }

    public Long getLiftedBy() {
        return liftedBy;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public Long getReportId() {
        return reportId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

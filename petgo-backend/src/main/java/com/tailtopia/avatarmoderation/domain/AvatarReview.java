package com.tailtopia.avatarmoderation.domain;

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
 * 头像审核记录（内容审核 story 5，建 {@code avatar_reviews} 表）。头像侧独立状态机 + 人工队列
 * （不复用帖子 {@code manual_review_queue}，与名称侧 {@code name_moderation_records} 并列同构；理由见 spec §4.1）。
 *
 * <p>命名映射链：列 snake_case ↔ 字段 camelCase。时间戳 UTC。{@code subjectId} 为内部外键值（USER_AVATAR=users.id /
 * PET_AVATAR=pet_profiles.id），绝不外露。{@code avatarUrl} 为<b>版本键</b>（D-CM3）：出结果/处置时与当前对象头像
 * 比对，不等即陈旧作废（{@code STALE_DISCARDED}）——不入业务日志（护栏 §5.6，头像 URL/签名 URL 禁记）。
 */
@Entity
@Table(name = "avatar_reviews")
public class AvatarReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private AvatarSubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    /** 送审时的头像 URL（版本键；禁入日志）。 */
    @Column(name = "avatar_url", nullable = false, length = 1024)
    private String avatarUrl;

    /** 三方图像审核综合风险分 0.000–1.000；降级/未评分为 null。numeric(4,3)。 */
    @Column(name = "risk_score", precision = 4, scale = 3)
    private BigDecimal riskScore;

    /** 审核结论；QUEUED（评分中）时为 null。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", length = 16)
    private AvatarReviewVerdict verdict;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AvatarReviewStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 8)
    private AvatarPriority priority = AvatarPriority.NORMAL;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AvatarReview() {
    }

    /** 新建送审记录（{@code QUEUED}，绑定 avatar_url 版本键）。 */
    public static AvatarReview queued(AvatarSubjectType subjectType, long subjectId, String avatarUrl) {
        AvatarReview r = new AvatarReview();
        r.subjectType = subjectType;
        r.subjectId = subjectId;
        r.avatarUrl = avatarUrl;
        r.status = AvatarReviewStatus.QUEUED;
        r.priority = AvatarPriority.NORMAL;
        return r;
    }

    /** 评分完成路由为终态/入队（AUTO_PASSED 或 MANUAL_PENDING）。riskScore 可空（降级）。 */
    public void applyScore(AvatarReviewStatus target, AvatarPriority priority, BigDecimal riskScore,
            AvatarReviewVerdict verdict) {
        this.status = target;
        this.priority = priority;
        this.riskScore = riskScore;
        this.verdict = verdict;
    }

    /** 陈旧作废（版本键失配或被新提交取代）：终态化为 RESOLVED/STALE_DISCARDED，静默丢弃（不处置、不推送）。 */
    public void discardAsStale() {
        this.status = AvatarReviewStatus.RESOLVED;
        this.verdict = AvatarReviewVerdict.STALE_DISCARDED;
    }

    /** 运营判过（终态，静默，不推送）。 */
    public void resolvePass() {
        this.status = AvatarReviewStatus.RESOLVED;
        this.verdict = AvatarReviewVerdict.PASS;
    }

    /** 运营判违规 → 已重置默认头像 + 推送（终态）。 */
    public void resolveViolation() {
        this.status = AvatarReviewStatus.RESOLVED;
        this.verdict = AvatarReviewVerdict.VIOLATION;
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

    public AvatarSubjectType getSubjectType() {
        return subjectType;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public BigDecimal getRiskScore() {
        return riskScore;
    }

    public AvatarReviewVerdict getVerdict() {
        return verdict;
    }

    public AvatarReviewStatus getStatus() {
        return status;
    }

    public AvatarPriority getPriority() {
        return priority;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

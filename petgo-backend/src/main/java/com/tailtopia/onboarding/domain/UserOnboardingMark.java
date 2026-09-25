package com.tailtopia.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 用户一次性引导标记（V1.3.0 批次 A · Story 5.4）。一行 = 这个用户看过这个引导。
 *
 * <h2>🔴 无 PII</h2>
 * 只有 userId、一个**内部常量键名**、一个时刻。键名是我们自己定义的（见
 * {@link OnboardingMarkKey}），不是用户输入，也不描述用户。
 *
 * <h2>🛡 置位的幂等靠唯一约束</h2>
 * {@code uq_user_onboarding_marks (user_id, mark_key)} —— 重复置位撞唯一键，
 * 结构上不可能出现第二行。不靠「先查有没有再插」（并发下两个请求都查到"没有"）。
 */
@Entity
@Table(name = "user_onboarding_marks")
public class UserOnboardingMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "mark_key", nullable = false, length = 64)
    private String markKey;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    protected UserOnboardingMark() {
    }

    public static UserOnboardingMark of(long userId, OnboardingMarkKey key) {
        UserOnboardingMark m = new UserOnboardingMark();
        m.userId = userId;
        m.markKey = key.wire();
        return m;
    }

    @PrePersist
    void onCreate() {
        this.firstSeenAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getMarkKey() {
        return markKey;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }
}

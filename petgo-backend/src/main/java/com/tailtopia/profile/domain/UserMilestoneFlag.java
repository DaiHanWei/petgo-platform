package com.tailtopia.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 账号级里程碑 flag 行（V1.4.2 social-topic Story 2.1 · D-4，{@code user_milestone_flags}）——
 * 一行 = 该账号已触发过该 flag（账号内只触发一次，不撤销）。
 *
 * <p>复合主键 {@code (user_id, flag_key)} 即幂等键。写入一律走仓储的 native
 * {@code INSERT … ON CONFLICT DO NOTHING}（{@code UserMilestoneFlagRepository#insertIfAbsent}），实体只用于读。
 */
@Entity
@Table(name = "user_milestone_flags")
@IdClass(UserMilestoneFlag.Key.class)
public class UserMilestoneFlag {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "flag_key", nullable = false, length = 32)
    private String flagKey;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /**
     * 账号级「已庆祝」（V1.5.0 onboarding-recall Story 2.2 · OR-6，可空，UTC）。只对 {@code LULUS_PEMULA} 有业务含义；
     * 写入只经 {@code UserMilestoneFlagRepository.markCelebrated}（条件 UPDATE），实体侧只读。
     */
    @Column(name = "celebrated_at", insertable = false, updatable = false)
    private Instant celebratedAt;

    protected UserMilestoneFlag() {
    }

    public Long getUserId() {
        return userId;
    }

    public String getFlagKey() {
        return flagKey;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCelebratedAt() {
        return celebratedAt;
    }

    /** 复合主键。 */
    public static class Key implements Serializable {

        private Long userId;
        private String flagKey;

        public Key() {
        }

        public Key(Long userId, String flagKey) {
            this.userId = userId;
            this.flagKey = flagKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key k)) {
                return false;
            }
            return Objects.equals(userId, k.userId) && Objects.equals(flagKey, k.flagKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, flagKey);
        }
    }
}

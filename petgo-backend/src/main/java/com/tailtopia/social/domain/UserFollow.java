package com.tailtopia.social.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 关注关系（V1.3.4 follow · Story 1.1 · FR-140 / AD-1）—— 一行 = {@code follower} 关注了 {@code followee}。
 *
 * <p>复合主键 {@code (follower_id, followee_id)} 即幂等键。写入一律走仓储的 native
 * {@code INSERT … ON CONFLICT DO NOTHING}（见 {@code UserFollowRepository#insertIfAbsent}），
 * 实体只用于读。
 */
@Entity
@Table(name = "user_follows")
@IdClass(UserFollow.Key.class)
public class UserFollow {

    @Id
    @Column(name = "follower_id", nullable = false)
    private Long followerId;

    @Id
    @Column(name = "followee_id", nullable = false)
    private Long followeeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserFollow() {
    }

    public Long getFollowerId() {
        return followerId;
    }

    public Long getFolloweeId() {
        return followeeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 复合主键。 */
    public static class Key implements Serializable {

        private Long followerId;
        private Long followeeId;

        public Key() {
        }

        public Key(Long followerId, Long followeeId) {
            this.followerId = followerId;
            this.followeeId = followeeId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key k)) {
                return false;
            }
            return Objects.equals(followerId, k.followerId) && Objects.equals(followeeId, k.followeeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(followerId, followeeId);
        }
    }
}

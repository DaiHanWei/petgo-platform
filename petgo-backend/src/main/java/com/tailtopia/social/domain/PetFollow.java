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
 * 追更宠物关系（V1.4.2 social-topic · Story 1.1 · D-1）—— 一行 = {@code follower} 追更了 {@code petProfile}。
 *
 * <p>复合主键 {@code (follower_id, pet_profile_id)} 即幂等键。写入一律走仓储的 native
 * {@code INSERT … ON CONFLICT DO NOTHING}（见 {@code PetFollowRepository#insertIfAbsent}），
 * 实体只用于读（1.3 关注流 JPQL 会 join 它，所以必须是 JPA 实体）。
 */
@Entity
@Table(name = "pet_follows")
@IdClass(PetFollow.Key.class)
public class PetFollow {

    @Id
    @Column(name = "follower_id", nullable = false)
    private Long followerId;

    @Id
    @Column(name = "pet_profile_id", nullable = false)
    private Long petProfileId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PetFollow() {
    }

    public Long getFollowerId() {
        return followerId;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 复合主键。 */
    public static class Key implements Serializable {

        private Long followerId;
        private Long petProfileId;

        public Key() {
        }

        public Key(Long followerId, Long petProfileId) {
            this.followerId = followerId;
            this.petProfileId = petProfileId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key k)) {
                return false;
            }
            return Objects.equals(followerId, k.followerId) && Objects.equals(petProfileId, k.petProfileId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(followerId, petProfileId);
        }
    }
}

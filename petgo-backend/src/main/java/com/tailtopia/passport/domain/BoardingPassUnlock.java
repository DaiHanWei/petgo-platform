package com.tailtopia.passport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 登机牌单张解锁（V1.3.2 Story 3.5 · 表 {@code boarding_pass_unlocks}）：宠物 × 当前场所一行。
 *
 * <p>写入一律走 JDBC / 原生 upsert（发起 {@code ON CONFLICT DO NOTHING}、发放口、合并改挂），本实体只供读与
 * {@code ddl-auto=validate}。{@link #isUnlocked()} = 已付且未被合并 supersede。
 */
@Entity(name = "BoardingPassUnlock")
@Table(name = "boarding_pass_unlocks")
public class BoardingPassUnlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Column(name = "pet_profile_id", nullable = false, updatable = false)
    private Long petProfileId;

    @Column(name = "place_id", nullable = false, insertable = false, updatable = false)
    private Long placeId;

    @Column(name = "unlocked_at", insertable = false, updatable = false)
    private Instant unlockedAt;

    @Column(name = "superseded_at", insertable = false, updatable = false)
    private Instant supersededAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected BoardingPassUnlock() {
    }

    public boolean isUnlocked() {
        return unlockedAt != null && supersededAt == null;
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public Instant getUnlockedAt() {
        return unlockedAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

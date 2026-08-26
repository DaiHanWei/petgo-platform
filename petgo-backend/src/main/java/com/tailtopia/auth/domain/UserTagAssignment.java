package com.tailtopia.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 用户标签分配（V1.1.6 Story 5.1 · FR-74）。
 *
 * <p>🛡 **没有状态列**：生效与否一律查询时按 {@code [startsAt, endsAt)} 判定（AD-9）。
 * {@code endsAt} 为空 = **永久分配**。
 *
 * <p>分配记录**只增不改**：超过展示上限（3 个）的记录保留在库、仅不展示。
 */
@Entity
@Table(name = "user_tag_assignments")
public class UserTagAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** 🛡 可空 = 永久分配（不设结束时间）。 */
    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserTagAssignment() {
    }

    public static UserTagAssignment of(long userId, long tagId, Instant startsAt, Instant endsAt) {
        UserTagAssignment a = new UserTagAssignment();
        a.userId = userId;
        a.tagId = tagId;
        a.startsAt = startsAt;
        a.endsAt = endsAt;
        return a;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getTagId() {
        return tagId;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }
}

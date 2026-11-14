package com.tailtopia.content.autolike;

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
import org.hibernate.annotations.Immutable;
import java.time.Instant;

/**
 * 自动点赞目标集 + 检查记录（{@code auto_like_targets}，V1.5.0 Story 2.1）。一帖一行：{@code post_id} 唯一 =
 * 每帖只检查一次；{@code left_at} 非空 = 已离开（或从未进入），不再回来。
 *
 * <p>生产写入走 {@link AutoLikeTargetRepository} 的原生 INSERT / UPDATE；实体主要供读取与测试造数。
 */
@Entity
@Immutable
@Table(name = "auto_like_targets")
public class AutoLikeTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(name = "post_created_at", nullable = false)
    private Instant postCreatedAt;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "like_count_at_check", nullable = false)
    private int likeCountAtCheck;

    @Column(name = "joined", nullable = false)
    private boolean joined;

    @Column(name = "left_at")
    private Instant leftAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_reason", length = 24)
    private AutoLikeLeaveReason leaveReason;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "last_liked_at")
    private Instant lastLikedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AutoLikeTarget() {
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPostId() {
        return postId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public Instant getPostCreatedAt() {
        return postCreatedAt;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public int getLikeCountAtCheck() {
        return likeCountAtCheck;
    }

    public boolean isJoined() {
        return joined;
    }

    public Instant getLeftAt() {
        return leftAt;
    }

    public AutoLikeLeaveReason getLeaveReason() {
        return leaveReason;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLastLikedAt() {
        return lastLikedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.tailtopia.content.autolike;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 自动点赞留档（{@code auto_like_logs}，V1.5.0 Story 2.2 / 2.3）：一条计划的赞一行。写入走原生 SQL，实体只读。
 * 只存 id 与计数，不存帖子内容。
 */
@Entity
@Immutable
@Table(name = "auto_like_logs")
public class AutoLikeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private Long runId;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(name = "run_date", nullable = false, updatable = false)
    private LocalDate runDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot", nullable = false, length = 8, updatable = false)
    private AutoLikeSlot slot;

    @Column(name = "planned_at", nullable = false)
    private Instant plannedAt;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AutoLikeLogStatus status;

    @Column(name = "virtual_user_id")
    private Long virtualUserId;

    @Column(name = "like_count_before")
    private Integer likeCountBefore;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "attempted_at")
    private Instant attemptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AutoLikeLog() {
    }

    public Long getId() {
        return id;
    }

    public Long getRunId() {
        return runId;
    }

    public Long getPostId() {
        return postId;
    }

    public LocalDate getRunDate() {
        return runDate;
    }

    public AutoLikeSlot getSlot() {
        return slot;
    }

    public Instant getPlannedAt() {
        return plannedAt;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public AutoLikeLogStatus getStatus() {
        return status;
    }

    public Long getVirtualUserId() {
        return virtualUserId;
    }

    public Integer getLikeCountBefore() {
        return likeCountBefore;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

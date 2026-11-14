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
 * 自动点赞轮次（{@code auto_like_runs}，V1.5.0 Story 2.2）：一槽一行。写入走原生 SQL（{@link AutoLikePlanner} /
 * 派发器），实体只读。
 */
@Entity
@Immutable
@Table(name = "auto_like_runs")
public class AutoLikeRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_date", nullable = false, updatable = false)
    private LocalDate runDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot", nullable = false, length = 8, updatable = false)
    private AutoLikeSlot slot;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private AutoLikeRunStatus status;

    @Column(name = "stop_reason", length = 32)
    private String stopReason;

    @Column(name = "launch", nullable = false)
    private boolean launch;

    @Column(name = "checked_count", nullable = false)
    private int checkedCount;

    @Column(name = "joined_count", nullable = false)
    private int joinedCount;

    @Column(name = "planned_count", nullable = false)
    private int plannedCount;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AutoLikeRun() {
    }

    public Long getId() {
        return id;
    }

    public LocalDate getRunDate() {
        return runDate;
    }

    public AutoLikeSlot getSlot() {
        return slot;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public AutoLikeRunStatus getStatus() {
        return status;
    }

    public String getStopReason() {
        return stopReason;
    }

    public boolean isLaunch() {
        return launch;
    }

    public int getCheckedCount() {
        return checkedCount;
    }

    public int getJoinedCount() {
        return joinedCount;
    }

    public int getPlannedCount() {
        return plannedCount;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

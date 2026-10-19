package com.tailtopia.growth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * 改等级表后的全量等级重算任务（V1.4.0 ① Story 4.1 · AD-12，{@code growth_recalc_jobs}）。
 * 状态机 {@code PENDING → RUNNING → DONE / FAILED}；同时最多一个进行中的任务（部分唯一索引）。
 */
@Entity
@Table(name = "growth_recalc_jobs")
public class GrowthRecalcJob {

    /** {@code RUNNING} 超过这么久视为僵死（进程中途重启），可重新执行。 */
    public static final Duration STALE_AFTER = Duration.ofMinutes(30);

    public enum Status {
        PENDING,
        RUNNING,
        DONE,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "requested_by", nullable = false)
    private long requestedBy;

    @Column(name = "upgraded")
    private Integer upgraded;

    @Column(name = "downgraded")
    private Integer downgraded;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected GrowthRecalcJob() {
    }

    public static GrowthRecalcJob pending(long requestedBy, Instant now) {
        GrowthRecalcJob j = new GrowthRecalcJob();
        j.status = Status.PENDING;
        j.requestedBy = requestedBy;
        j.createdAt = now;
        return j;
    }

    /** 进行中（排队或执行中）。 */
    public boolean isActive() {
        return status == Status.PENDING || status == Status.RUNNING;
    }

    /**
     * 失败，或进行中已超过 {@link #STALE_AFTER}（僵死）→ 可重新执行。{@code PENDING} 也算：提交后的异步执行可能因进程重启
     * 丢失，没有扫描任务会再拾起它，不放开就会让等级表永远「重算进行中」不可保存。
     */
    public boolean isRetryable(Instant now) {
        if (status == Status.FAILED) {
            return true;
        }
        Instant since = startedAt != null ? startedAt : createdAt;
        return (status == Status.RUNNING || status == Status.PENDING) && since != null
                && since.plus(STALE_AFTER).isBefore(now);
    }

    public Long getId() {
        return id;
    }

    public Status getStatus() {
        return status;
    }

    public long getRequestedBy() {
        return requestedBy;
    }

    public Integer getUpgraded() {
        return upgraded;
    }

    public Integer getDowngraded() {
        return downgraded;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}

package com.tailtopia.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 注销作业（Story 7.3，{@code account_deletions} 表）。DB 状态机：PENDING→PROCESSING→DONE/FAILED + retry_count。
 *
 * <p>本表绝不落 PII：仅 user 代理 id + 状态机进度 + 受理/完成时间（合规留证）。
 */
@Entity
@Table(name = "account_deletions")
public class AccountDeletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeletionStatus status = DeletionStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AccountDeletion() {
    }

    public static AccountDeletion request(long userId) {
        AccountDeletion d = new AccountDeletion();
        d.userId = userId;
        d.status = DeletionStatus.PENDING;
        return d;
    }

    public void markProcessing() {
        this.status = DeletionStatus.PROCESSING;
    }

    public void markDone() {
        this.status = DeletionStatus.DONE;
        this.completedAt = Instant.now();
    }

    public void markFailed() {
        this.status = DeletionStatus.FAILED;
        this.retryCount++;
    }

    @PrePersist
    void onCreate() {
        this.requestedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public DeletionStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }
}

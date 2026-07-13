package com.tailtopia.triage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 每月免费额度（Story 2.1，建 {@code user_monthly_free_quota} 表）。一人一月一行（唯一
 * {@code (user_id, period)}），{@code period} 为 {@code YYYY-MM}（<b>WIB</b>）——换月即新行 = 惰性重置。
 *
 * <p><b>并发不超发</b>（FR-43B/NFR-3）：{@code used_count} 递增一律走
 * {@code UserMonthlyFreeQuotaRepository.tryConsume} 原子条件 UPDATE（{@code WHERE used_count < :limit}），
 * 库级 {@code CHECK(used_count>=0)} 兜底。<b>禁应用层「读-改-写」</b>（并发丢更新）。本实体只用于只读映射。
 */
@Entity
@Table(name = "user_monthly_free_quota")
public class UserMonthlyFreeQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "period", nullable = false, updatable = false, length = 7)
    private String period;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserMonthlyFreeQuota() {
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getPeriod() {
        return period;
    }

    public int getUsedCount() {
        return usedCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

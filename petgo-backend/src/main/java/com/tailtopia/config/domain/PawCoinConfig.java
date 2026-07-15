package com.tailtopia.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * PawCoin 配置（Story 9.2，AB-6A/6C）。固定单行 {@code id=1}。
 * {@code premiumRate}=退款转 PawCoin 溢价 %（仅「未交付+转币」分支用，反套利 C-1）；
 * {@code topupPaused}=充值暂停（浮存门槛 AB-6C）。
 */
@Entity
@Table(name = "pawcoin_config")
public class PawCoinConfig {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "premium_rate", nullable = false)
    private int premiumRate;

    @Column(name = "topup_paused", nullable = false)
    private boolean topupPaused;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PawCoinConfig() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public int getPremiumRate() {
        return premiumRate;
    }

    public void setPremiumRate(int premiumRate) {
        this.premiumRate = premiumRate;
    }

    public boolean isTopupPaused() {
        return topupPaused;
    }

    public void setTopupPaused(boolean topupPaused) {
        this.topupPaused = topupPaused;
    }
}

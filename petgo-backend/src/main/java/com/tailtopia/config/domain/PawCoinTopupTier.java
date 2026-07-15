package com.tailtopia.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * PawCoin 充值档位（Story 9.2，AB-6B）。可运营增删/启停；**保底 ≥1 启用**（业务层强校验）。
 * {@code amountIdr} = 到账 koin（1:1）。{@code tierKey} 稳定对外标识。
 */
@Entity
@Table(name = "pawcoin_topup_tiers")
public class PawCoinTopupTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tier_key", nullable = false)
    private String tierKey;

    @Column(name = "amount_idr", nullable = false)
    private long amountIdr;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PawCoinTopupTier() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTierKey() {
        return tierKey;
    }

    public long getAmountIdr() {
        return amountIdr;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}

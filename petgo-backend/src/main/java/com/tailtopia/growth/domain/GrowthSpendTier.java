package com.tailtopia.growth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 支付用途 → 消费返档位（V1.4.0 ① Story 1.3 · 架构 AD-4，{@code growth_spend_tiers}）。 */
@Entity
@Table(name = "growth_spend_tiers")
public class GrowthSpendTier {

    @Id
    @Column(name = "purpose", length = 16)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 16)
    private SpendTier tier;

    @Column(name = "locked", nullable = false)
    private boolean locked;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected GrowthSpendTier() {
    }

    public static GrowthSpendTier of(String purpose, SpendTier tier, boolean locked) {
        GrowthSpendTier t = new GrowthSpendTier();
        t.purpose = purpose;
        t.tier = tier;
        t.locked = locked;
        t.updatedAt = Instant.now();
        return t;
    }

    public String getPurpose() {
        return purpose;
    }

    public SpendTier getTier() {
        return tier;
    }

    public boolean isLocked() {
        return locked;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }
}

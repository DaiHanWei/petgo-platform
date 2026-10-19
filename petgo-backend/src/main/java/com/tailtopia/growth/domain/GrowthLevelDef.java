package com.tailtopia.growth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 等级表一行（V1.4.0 ① 架构 AD-4，{@code growth_levels}）。段位与等级边界的权威来源；只能末尾追加。
 */
@Entity
@Table(name = "growth_levels")
public class GrowthLevelDef {

    @Id
    @Column(name = "level")
    private Integer level;

    @Column(name = "threshold", nullable = false)
    private long threshold;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 16)
    private GrowthTier tier;

    protected GrowthLevelDef() {
    }

    public static GrowthLevelDef of(int level, long threshold, GrowthTier tier) {
        GrowthLevelDef d = new GrowthLevelDef();
        d.level = level;
        d.threshold = threshold;
        d.tier = tier;
        return d;
    }

    public int getLevel() {
        return level;
    }

    public long getThreshold() {
        return threshold;
    }

    public GrowthTier getTier() {
        return tier;
    }
}

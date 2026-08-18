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

    @Column(name = "premium_fixed", nullable = false)
    private long premiumFixed;

    /**
     * 🔴 平台责任<b>补偿</b>溢价比例（V1.4.0 Story 3.5 / C-9 / D-8）。
     *
     * <p><b>与 {@link #premiumRate}（激励溢价）是两个独立配置项，绝不可共用同一数值。</b>
     * 激励溢价用于「未交付 + 转币」分支的反套利；补偿溢价用于平台责任退货时
     * PawCoin 段不退现金的安抚（C-9）。
     * 写成单值会<b>连带毁掉 AB-13A 的售后成本口径与 AB-6C 的浮存归因</b>，
     * 且是<b>静默错误</b>——不报错，只是两个报表的数字一直不对，且没人知道该信哪个。
     */
    @Column(name = "compensation_premium_rate", nullable = false)
    private int compensationPremiumRate;

    /** 补偿溢价的单笔上限（最小币种单位）。0 = 不设上限。 */
    @Column(name = "compensation_premium_cap", nullable = false)
    private long compensationPremiumCap;

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

    public int getCompensationPremiumRate() {
        return compensationPremiumRate;
    }

    public long getCompensationPremiumCap() {
        return compensationPremiumCap;
    }

    /** 🔴 只改补偿溢价，绝不触碰激励溢价（两者独立）。 */
    public void applyCompensationPremium(int rate, long cap) {
        this.compensationPremiumRate = rate;
        this.compensationPremiumCap = cap;
    }

    public int getPremiumRate() {
        return premiumRate;
    }

    public void setPremiumRate(int premiumRate) {
        this.premiumRate = premiumRate;
    }

    public long getPremiumFixed() {
        return premiumFixed;
    }

    public void setPremiumFixed(long premiumFixed) {
        this.premiumFixed = premiumFixed;
    }

    /**
     * 退款转 PawCoin 溢价（bonus）= 基础退款额 × premiumRate% + premiumFixed。
     * 仅「未交付 + 转币」分支给（反套利 C-1，由 RefundService 门控）；两参数均后台可配。
     */
    public long refundPawcoinPremium(long baseAmount) {
        return baseAmount * premiumRate / 100 + premiumFixed;
    }

    public boolean isTopupPaused() {
        return topupPaused;
    }

    public void setTopupPaused(boolean topupPaused) {
        this.topupPaused = topupPaused;
    }
}

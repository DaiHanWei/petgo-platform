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

    @Column(name = "topup_paused", nullable = false)
    private boolean topupPaused;

    /**
     * 分享奖励**总开关**（V1.1.6 Story 18.1 · AC6）。
     *
     * <p>🔴 存在的唯一理由是「发现被刷要能立刻全线关掉」，所以它必须<b>比任何渠道层配置优先</b>。
     * 🛡 关掉时一律不发币、不展示提示，但<b>分享功能本身不受影响</b>。
     */
    @Column(name = "share_reward_enabled", nullable = false)
    private boolean shareRewardEnabled;

    /**
     * 单账号每 WIB 自然月通过**所有分享类行为**可免费获得的 PawCoin 上限（Story 18.1 · AC1）。
     *
     * <p>🛡 按「所有分享类行为」合一，<b>不是按渠道各算一份</b>。
     * ⚠️ 种子值 2000 是**待产品确认**的取值：1 PawCoin = 1 IDR、HD 解锁价种子 5000
     * ⇒ 攒满 2.5 个月换一次 HD 解锁。这个比值正是 OQ-C1 要运营看见的（18-3 配置页同屏算出）。
     */
    @Column(name = "share_reward_monthly_cap", nullable = false)
    private long shareRewardMonthlyCap;

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

    public boolean isShareRewardEnabled() {
        return shareRewardEnabled;
    }

    public void setShareRewardEnabled(boolean v) {
        this.shareRewardEnabled = v;
    }

    public long getShareRewardMonthlyCap() {
        return shareRewardMonthlyCap;
    }

    public void setShareRewardMonthlyCap(long v) {
        this.shareRewardMonthlyCap = v;
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

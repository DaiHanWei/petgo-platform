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
     * <p>🔴 种子值 <b>0 = 不发币</b>（2026-08-26 产品决定）：功能随版本上线，
     * 但默认一分不发，等产品在后台把数配上。
     * ⚠️ 不预置"合理值"是有意的 —— 这个数的正确取值取决于它与 HD 解锁价的**比值**
     * （攒满几个月换一次高清），而那正是 OQ-C1 要产品看着定的东西；
     * 预置一个数等于「没人做过决定，但线上已经在按它发币了」。
     */
    @Column(name = "share_reward_monthly_cap", nullable = false)
    private long shareRewardMonthlyCap;

    /**
     * 身份证卡面分享一次发几枚（Story 18.2 · 渠道层）。🔴 种子值 <b>0 = 不发币</b>。
     *
     * <p>⚠️ 这是**渠道层**配置，与 {@link #shareRewardMonthlyCap} 的全局层是两层：
     * 全局层管「一个账号一个月最多免费拿多少」，本项管「这个渠道一次发几枚」。
     * 🔴 全局总开关优先——关掉它，本项配成什么都不发。
     */
    @Column(name = "id_card_share_reward", nullable = false)
    private long idCardShareReward;

    /**
     * 身份证卡面分享的**日上限次数**（Story 18.2 · 渠道层）。🔴 种子值 <b>0 = 不发币</b>。
     *
     * <p>⚠️ 三个数（月上限 / 每次几枚 / 日上限）**任意一个是 0 都不会发** —— 闸门是串联的。
     *
     * <p>⚠️ 本渠道另有「按宠物档案去重、一个档案只发一次」的更强约束，
     * 所以日上限对它是**冗余的保险**；它存在是为了后续渠道接入时这一层已经就位。
     */
    @Column(name = "id_card_share_daily_cap", nullable = false)
    private int idCardShareDailyCap;

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

    public long getIdCardShareReward() {
        return idCardShareReward;
    }

    public void setIdCardShareReward(long v) {
        this.idCardShareReward = v;
    }

    public int getIdCardShareDailyCap() {
        return idCardShareDailyCap;
    }

    public void setIdCardShareDailyCap(int v) {
        this.idCardShareDailyCap = v;
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

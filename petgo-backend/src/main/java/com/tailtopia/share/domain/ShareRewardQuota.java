package com.tailtopia.share.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 分享奖励的月度额度记账（V1.1.6 Story 18.1）。一行 = 某账号在某个 WIB 自然月已拿到的分享奖励总量。
 *
 * <p>🛡 <b>没有渠道列</b>（AC1）：额度按「所有分享类行为」合一。
 * 加了渠道列就变成按渠道各算一份，等于上限乘以渠道数 ——
 * 而这一层存在的全部意义就是「一个账号一个月最多免费拿这么多」。
 *
 * <p>⚠️ {@code period} 是 <b>WIB</b> 的 {@code YYYY-MM}，与 {@code user_monthly_free_quota}
 * 同一口径（Story 2.1 已定死）。换月自然产生新行 = 惰性重置，不需要 {@code @Scheduled}。
 */
@Entity
@Table(name = "share_reward_quotas")
public class ShareRewardQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "period", nullable = false, length = 7)
    private String period;

    @Column(name = "granted_coins", nullable = false)
    private long grantedCoins;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShareRewardQuota() {
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

    public long getGrantedCoins() {
        return grantedCoins;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

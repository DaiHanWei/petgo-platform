package com.tailtopia.share.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 年龄卡分享奖励的发放留痕（V1.3.0 批次 A · Story 5.3）。一行 = 一次成功发放。
 *
 * <h2>🔴 去重键是幂等键，不是宠物档案（决策 A-8）</h2>
 * 身份证渠道按 {@code pet_profile_id} 唯一（一个档案一辈子一次）。年龄卡**不是**：
 * 同一只宠物隔几个月再生成是不同的分享物。所以本表<b>没有 petProfileId 字段，
 * 更没有它的唯一约束</b> —— 塞进那张表要么撞唯一键，要么就得把那条约束拆掉，
 * 而那条约束正是身份证渠道的幂等本身。
 *
 * <h2>🛡 幂等靠唯一约束，不靠先查再插（AC8）</h2>
 * {@code uq_age_card_share_rewards_idem} 让「一次分享只发一次」在约束层面成立：
 * 重复上报撞唯一键 ⇒ 结构上不可能重复发。
 */
@Entity
@Table(name = "age_card_share_rewards")
public class AgeCardShareReward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "coins", nullable = false)
    private long coins;

    /** WIB 当地日期，用于日上限判定。 */
    @Column(name = "share_date", nullable = false)
    private LocalDate shareDate;

    /** 🔴 去重键：一次分享动作一个。 */
    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgeCardShareReward() {
    }

    public static AgeCardShareReward of(long userId, long coins, LocalDate shareDate,
            String idempotencyKey) {
        AgeCardShareReward r = new AgeCardShareReward();
        r.userId = userId;
        r.coins = coins;
        r.shareDate = shareDate;
        r.idempotencyKey = idempotencyKey;
        return r;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public long getCoins() {
        return coins;
    }

    public LocalDate getShareDate() {
        return shareDate;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

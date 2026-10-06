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
 * Tailsonality 结果卡 / 配型卡分享奖励的发放留痕（V1.3.2 Story 4.5 · AD-13）。一行 = 一次成功发放。
 *
 * <h2>🔴 去重键 = 宠物档案 × 卡类型</h2>
 * {@code UNIQUE (pet_profile_id, card_type)}：每只宠物每卡类型一辈子一次（重测 / 再打卡都不再发）。
 * {@code petProfileId} 可空 —— 删档由外键 {@code ON DELETE SET NULL} 置空，留痕行保留（AD-17）；
 * PostgreSQL 唯一约束对 NULL 不判重，置空后的历史行互不相撞。
 */
@Entity
@Table(name = "tailsonality_share_rewards")
public class TailsonalityShareReward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 领奖时的宠物；删档后为 null。 */
    @Column(name = "pet_profile_id")
    private Long petProfileId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** {@code RESULT} = 结果卡；{@code MATCH} = 配型卡。 */
    @Column(name = "card_type", nullable = false, length = 16)
    private String cardType;

    @Column(name = "coins", nullable = false)
    private long coins;

    /** WIB 当地日期，用于日上限判定。 */
    @Column(name = "share_date", nullable = false)
    private LocalDate shareDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TailsonalityShareReward() {
    }

    public static TailsonalityShareReward of(long petProfileId, long userId, String cardType, long coins, LocalDate shareDate) {
        TailsonalityShareReward r = new TailsonalityShareReward();
        r.petProfileId = petProfileId;
        r.userId = userId;
        r.cardType = cardType;
        r.coins = coins;
        r.shareDate = shareDate;
        return r;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCardType() {
        return cardType;
    }

    public long getCoins() {
        return coins;
    }

    public LocalDate getShareDate() {
        return shareDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

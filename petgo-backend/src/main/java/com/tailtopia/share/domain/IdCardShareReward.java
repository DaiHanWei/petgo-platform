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
 * 身份证卡面分享奖励的发放留痕（V1.1.6 Story 18.2）。一行 = 某个宠物档案拿过这份奖励。
 *
 * <h2>🔴 去重键是宠物档案，不是卡（AC4）</h2>
 * {@code createCard()} 无数量限制且不要求档案 ⇒ 卡可无限建 ⇒ <b>按卡去重等于无去重</b>。
 * 补充 PRD 的 A-C1「按卡去重」假设已废弃。
 *
 * <h2>🛡 幂等靠唯一约束，不靠先查再插（AC7）</h2>
 * {@code uq_id_card_share_rewards_profile} 让「一个档案只发一次」在**约束层面**成立：
 * 并发两次分享，第二次撞唯一键 ⇒ 结构上不可能重复发。
 * 🔴 「先查有没有再插」是典型的并发双发 —— 两个请求都查到"没有"。
 *
 * <p>{@code cardId} 只是留痕（哪张卡触发的），<b>不参与去重</b>。
 */
@Entity
@Table(name = "id_card_share_rewards")
public class IdCardShareReward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 🔴 去重键。 */
    @Column(name = "pet_profile_id", nullable = false)
    private Long petProfileId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 留痕用：哪张卡触发的。⚠️ 不参与去重。 */
    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "coins", nullable = false)
    private long coins;

    /** WIB 当地日期，用于日上限判定。 */
    @Column(name = "share_date", nullable = false)
    private LocalDate shareDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdCardShareReward() {
    }

    public static IdCardShareReward of(long petProfileId, long userId, long cardId, long coins,
            LocalDate shareDate) {
        IdCardShareReward r = new IdCardShareReward();
        r.petProfileId = petProfileId;
        r.userId = userId;
        r.cardId = cardId;
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

    public Long getCardId() {
        return cardId;
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

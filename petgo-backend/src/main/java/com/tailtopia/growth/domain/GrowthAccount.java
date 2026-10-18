package com.tailtopia.growth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 成长值账户（V1.4.0 ① 架构 AD-1）。一行 = 一个用户的累计成长值与当前等级。
 *
 * <p>行懒建（首次入账 {@code INSERT … ON CONFLICT DO NOTHING}）；<b>无账户行 = Lv1、累计 0</b>。
 * 写入只经 {@code GrowthLedgerService}（§5.4），且总在 {@code SELECT … FOR UPDATE} 锁住本行之后。
 */
@Entity
@Table(name = "growth_accounts")
public class GrowthAccount {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "lifetime_total", nullable = false)
    private long lifetimeTotal;

    /** PRD 预留，1.4.0 不读写。 */
    @Column(name = "balance", nullable = false)
    private long balance;

    @Column(name = "level", nullable = false)
    private int level;

    @Enumerated(EnumType.STRING)
    @Column(name = "frame_choice", length = 16)
    private FrameChoice frameChoice;

    @Column(name = "display_level")
    private Integer displayLevel;

    @Column(name = "celebrate_from_level")
    private Integer celebrateFromLevel;

    @Column(name = "celebrate_to_level")
    private Integer celebrateToLevel;

    @Column(name = "backfill_popup_pending", nullable = false)
    private boolean backfillPopupPending;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GrowthAccount() {
    }

    /** 测试 / 内存构造用；生产路径的行由 {@code GrowthAccountRepository#insertIfAbsent} 建。 */
    public static GrowthAccount of(long userId, long lifetimeTotal, int level) {
        GrowthAccount a = new GrowthAccount();
        a.userId = userId;
        a.lifetimeTotal = lifetimeTotal;
        a.level = level;
        a.createdAt = Instant.now();
        a.updatedAt = a.createdAt;
        return a;
    }

    /** 累计变化（带符号）。调用方保证结果不为负（扣回已截断）。 */
    public void addLifetime(long delta, Instant now) {
        long next = lifetimeTotal + delta;
        if (next < 0) {
            throw new IllegalStateException("lifetime_total 不得为负");
        }
        this.lifetimeTotal = next;
        this.updatedAt = now;
    }

    public void changeLevel(int newLevel, Instant now) {
        this.level = newLevel;
        this.updatedAt = now;
    }

    public Long getUserId() {
        return userId;
    }

    public long getLifetimeTotal() {
        return lifetimeTotal;
    }

    public int getLevel() {
        return level;
    }

    public FrameChoice getFrameChoice() {
        return frameChoice;
    }

    public Integer getDisplayLevel() {
        return displayLevel;
    }

    public Integer getCelebrateFromLevel() {
        return celebrateFromLevel;
    }

    public Integer getCelebrateToLevel() {
        return celebrateToLevel;
    }

    public boolean isBackfillPopupPending() {
        return backfillPopupPending;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

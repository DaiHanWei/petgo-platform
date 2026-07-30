package com.tailtopia.pay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * PawCoin 用户流水（Story 1.2，建 {@code pawcoin_transactions} 表）。每次钱包变动写一条，关联
 * {@code entry_group} 与总账，供用户余额/流水页（1.4）读。<b>充值失败不写本表</b>——仅在成功事务内写
 * （由 1.3 调用方在 {@code PawCoinWalletService} 成功路径落）。
 */
@Entity
@Table(name = "pawcoin_transactions")
public class PawCoinTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 变动量（+充值/退回、-消费）。 */
    @Column(name = "delta", nullable = false, updatable = false)
    private long delta;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16, updatable = false)
    private PawCoinTxnType type;

    @Column(name = "ref_type", length = 24, updatable = false)
    private String refType;

    @Column(name = "ref_id", updatable = false)
    private Long refId;

    @Column(name = "entry_group", length = 36, updatable = false)
    private String entryGroup;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PawCoinTransaction() {
    }

    public static PawCoinTransaction of(long userId, long delta, PawCoinTxnType type,
            String refType, Long refId, String entryGroup) {
        PawCoinTransaction t = new PawCoinTransaction();
        t.userId = userId;
        t.delta = delta;
        t.type = type;
        t.refType = refType;
        t.refId = refId;
        t.entryGroup = entryGroup;
        return t;
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

    public long getDelta() {
        return delta;
    }

    public PawCoinTxnType getType() {
        return type;
    }

    public String getRefType() {
        return refType;
    }

    public Long getRefId() {
        return refId;
    }

    public String getEntryGroup() {
        return entryGroup;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

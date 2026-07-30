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
 * 总账分录（Story 1.2，建 {@code ledger_entries} 表）。<b>append-only 双分录</b>——单一事实源，一切资金
 * 变动可勾稽对账（FR-NFR-1）。
 *
 * <p><b>append-only 铁律</b>：无 {@code @PreUpdate}、无 {@code updated_at}、<b>无任何改金额/方向的 setter</b>；
 * 更正走反向补偿分录（新行），绝不改旧行。一次资金事件的一组分录共享 {@code entryGroup} 且
 * Σ(DEBIT)==Σ(CREDIT)（由 {@code LedgerService.post} 强制）。{@code (idempotency_key, account, direction)}
 * 唯一 = 幂等/重放去重的库级权威。
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_group", nullable = false, length = 36, updatable = false)
    private String entryGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "account", nullable = false, length = 20, updatable = false)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8, updatable = false)
    private LedgerDirection direction;

    /** 金额（最小币种单位整型，恒 > 0；方向由 {@link #direction} 表达）。 */
    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(name = "ref_type", length = 24, updatable = false)
    private String refType;

    @Column(name = "ref_id", updatable = false)
    private Long refId;

    @Column(name = "idempotency_key", nullable = false, length = 80, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    /** 建一条分录（仅由 {@code LedgerService.post} 在校验借贷平衡后调用）。 */
    public static LedgerEntry of(String entryGroup, LedgerAccount account, LedgerDirection direction,
            long amount, Long userId, String refType, Long refId, String idempotencyKey) {
        LedgerEntry e = new LedgerEntry();
        e.entryGroup = entryGroup;
        e.account = account;
        e.direction = direction;
        e.amount = amount;
        e.userId = userId;
        e.refType = refType;
        e.refId = refId;
        e.idempotencyKey = idempotencyKey;
        return e;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
    // 故意无 @PreUpdate / 无金额 setter —— append-only。

    public Long getId() {
        return id;
    }

    public String getEntryGroup() {
        return entryGroup;
    }

    public LedgerAccount getAccount() {
        return account;
    }

    public LedgerDirection getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }

    public Long getUserId() {
        return userId;
    }

    public String getRefType() {
        return refType;
    }

    public Long getRefId() {
        return refId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

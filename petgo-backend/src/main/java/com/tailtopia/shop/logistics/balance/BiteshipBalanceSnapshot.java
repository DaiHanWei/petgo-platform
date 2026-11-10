package com.tailtopia.shop.logistics.balance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 运营手填的 Biteship 实际余额（V1.4.2 shop-admin Story 4.1 · AD-13，{@code biteship_balance_snapshots}）。
 * Biteship 没有余额接口：最新一条是预估起点，此后创建的运单从此数往下扣；再录一次就从新数重扣。只增不改。
 */
@Entity
@Table(name = "biteship_balance_snapshots")
public class BiteshipBalanceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    /** 录入时系统的预估余额；此前从未录入为空。 */
    @Column(name = "estimate_before", updatable = false)
    private Long estimateBefore;

    @Column(name = "recorded_by", updatable = false)
    private Long recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected BiteshipBalanceSnapshot() {
    }

    public static BiteshipBalanceSnapshot record(long amount, Long estimateBefore, Long recordedBy, Instant now) {
        if (amount < 0) {
            throw new IllegalArgumentException("balance must be >= 0");
        }
        BiteshipBalanceSnapshot s = new BiteshipBalanceSnapshot();
        s.amount = amount;
        s.estimateBefore = estimateBefore;
        s.recordedBy = recordedBy;
        s.recordedAt = now;
        return s;
    }

    public Long getId() {
        return id;
    }

    public long getAmount() {
        return amount;
    }

    public Long getEstimateBefore() {
        return estimateBefore;
    }

    public Long getRecordedBy() {
        return recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}

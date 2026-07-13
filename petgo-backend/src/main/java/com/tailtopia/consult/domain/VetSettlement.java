package com.tailtopia.consult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 兽医月度结算（Story 3.7，{@code vet_settlements}）。每月 1 日 WIB {@code @Scheduled} 聚合上月 COMPLETED 订单的
 * {@code vet_payout} 快照生成一行（唯一 {@code (vet_id, period)}，幂等）。
 *
 * <p>{@code period} 为 {@code YYYY-MM}（<b>WIB</b>，Asia/Jakarta，刻意偏离全局 UTC，照 2-1）。金额 bigint IDR。
 * {@code status} {@code PENDING}（生成即待结算）→ {@code SETTLED}（管理端 9-5 对账打款后标记；本 story 只产 PENDING）。
 */
@Entity
@Table(name = "vet_settlements")
public class VetSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vet_id", nullable = false, updatable = false)
    private Long vetId;

    /** YYYY-MM（WIB）。 */
    @Column(name = "period", nullable = false, updatable = false, length = 7)
    private String period;

    @Column(name = "order_count", nullable = false)
    private int orderCount;

    /** 成交额合计（IDR）。 */
    @Column(name = "gross_amount", nullable = false)
    private long grossAmount;

    /** 到手合计（IDR，Σ vet_payout 快照）。 */
    @Column(name = "payout_amount", nullable = false)
    private long payoutAmount;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VetSettlement() {
    }

    /** 生成月结（PENDING）：聚合值 + 生成时刻。 */
    public static VetSettlement of(long vetId, String period, int orderCount, long grossAmount,
            long payoutAmount, Instant generatedAt) {
        VetSettlement s = new VetSettlement();
        s.vetId = vetId;
        s.period = period;
        s.orderCount = orderCount;
        s.grossAmount = grossAmount;
        s.payoutAmount = payoutAmount;
        s.status = "PENDING";
        s.generatedAt = generatedAt;
        return s;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getVetId() {
        return vetId;
    }

    public String getPeriod() {
        return period;
    }

    public int getOrderCount() {
        return orderCount;
    }

    public long getGrossAmount() {
        return grossAmount;
    }

    public long getPayoutAmount() {
        return payoutAmount;
    }

    public String getStatus() {
        return status;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}

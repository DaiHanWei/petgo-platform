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
 * <p><b>财务对账状态（Story 9.5，AB-8D）</b>：{@code PENDING_FINANCE}（生成即待财务打款）→ {@code PAID}
 * （确认打款 + 凭证）→ {@code ARCHIVED}（归档）。兽医侧经 {@code VetIncomeResponse.ofSettlement} 映射为
 * PENDING/SETTLED 2 态（App 零改）。
 */
@Entity
@Table(name = "vet_settlements")
public class VetSettlement {

    public static final String PENDING_FINANCE = "PENDING_FINANCE";
    public static final String PAID = "PAID";
    public static final String ARCHIVED = "ARCHIVED";

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

    @Column(name = "payment_proof", length = 512)
    private String paymentProof;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "settled_by")
    private Long settledBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VetSettlement() {
    }

    /** 生成月结（PENDING_FINANCE 待财务打款）：聚合值 + 生成时刻。 */
    public static VetSettlement of(long vetId, String period, int orderCount, long grossAmount,
            long payoutAmount, Instant generatedAt) {
        VetSettlement s = new VetSettlement();
        s.vetId = vetId;
        s.period = period;
        s.orderCount = orderCount;
        s.grossAmount = grossAmount;
        s.payoutAmount = payoutAmount;
        s.status = PENDING_FINANCE;
        s.generatedAt = generatedAt;
        return s;
    }

    /** 财务确认打款（Story 9.5）：仅 {@code PENDING_FINANCE}→{@code PAID}，记凭证/时刻/操作人。非法态抛。 */
    public void markPaid(String proof, long adminId) {
        if (!PENDING_FINANCE.equals(status)) {
            throw com.tailtopia.shared.error.AppException.validation("仅待打款月结可确认打款");
        }
        this.status = PAID;
        this.paymentProof = proof;
        this.paidAt = Instant.now();
        this.settledBy = adminId;
    }

    /** 归档（Story 9.5）：仅 {@code PAID}→{@code ARCHIVED}。非法态抛。 */
    public void archive(long adminId) {
        if (!PAID.equals(status)) {
            throw com.tailtopia.shared.error.AppException.validation("仅已打款月结可归档");
        }
        this.status = ARCHIVED;
        this.archivedAt = Instant.now();
        this.settledBy = adminId;
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

    public String getPaymentProof() {
        return paymentProof;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Long getSettledBy() {
        return settledBy;
    }
}

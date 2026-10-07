package com.tailtopia.purchase.domain;

import com.tailtopia.pay.domain.PayChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 一次性解锁购买行（V1.3.2 Story 3.1 · 表 {@code keepsake_purchases}）。资金流水：删档 / 注销都保留。
 *
 * <p>{@link #priceIdr} 是<b>唯一成交价</b>：QRIS = 意图金额（到账时以到账金额覆盖），PawCoin = 扣币额。
 */
@Entity(name = "KeepsakePurchase")
@Table(name = "keepsake_purchases")
public class KeepsakePurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "sku", nullable = false, updatable = false, length = 16)
    private KeepsakeSku sku;

    @Column(name = "ref_id", nullable = false, updatable = false)
    private Long refId;

    @Column(name = "pet_profile_id")
    private Long petProfileId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "price_idr", nullable = false)
    private long priceIdr;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_channel", nullable = false, updatable = false, length = 16)
    private PayChannel payChannel;

    @Column(name = "payment_intent_id", updatable = false)
    private Long paymentIntentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private KeepsakePurchaseStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected KeepsakePurchase() {
    }

    /** QRIS 发起：PENDING，价格取意图金额。 */
    public static KeepsakePurchase pendingQris(String publicToken, KeepsakeRef ref, long userId, long intentAmount,
            long paymentIntentId, Instant now) {
        KeepsakePurchase p = base(publicToken, ref, userId, intentAmount, PayChannel.QRIS, now);
        p.paymentIntentId = paymentIntentId;
        p.status = KeepsakePurchaseStatus.PENDING;
        return p;
    }

    /** PawCoin 当场成交：PAID，价格 = 扣币额。 */
    public static KeepsakePurchase paidPawcoin(String publicToken, KeepsakeRef ref, long userId, long coins, Instant now) {
        KeepsakePurchase p = base(publicToken, ref, userId, coins, PayChannel.PAWCOIN, now);
        p.status = KeepsakePurchaseStatus.PAID;
        p.paidAt = now;
        return p;
    }

    private static KeepsakePurchase base(String publicToken, KeepsakeRef ref, long userId, long price,
            PayChannel channel, Instant now) {
        KeepsakePurchase p = new KeepsakePurchase();
        p.publicToken = publicToken;
        p.sku = ref.sku();
        p.refId = ref.refId();
        p.petProfileId = ref.petProfileId();
        p.userId = userId;
        p.priceIdr = price;
        p.payChannel = channel;
        p.createdAt = now;
        return p;
    }

    /** 到账：置 PAID，成交价以到账金额为准。 */
    public void markPaid(long amount, Instant now) {
        this.status = KeepsakePurchaseStatus.PAID;
        this.priceIdr = amount;
        this.paidAt = now;
    }

    public void markStatus(KeepsakePurchaseStatus next) {
        this.status = next;
    }

    /** 发放结果回写：GRANTED 保持 PAID；ALREADY_UNLOCKED → DUPLICATE_PAID；REF_MISSING → ORPHAN_PAID。 */
    public void applyGrantOutcome(GrantOutcome outcome) {
        this.status = switch (outcome) {
            case GRANTED -> KeepsakePurchaseStatus.PAID;
            case ALREADY_UNLOCKED -> KeepsakePurchaseStatus.DUPLICATE_PAID;
            case REF_MISSING -> KeepsakePurchaseStatus.ORPHAN_PAID;
        };
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public KeepsakeSku getSku() {
        return sku;
    }

    public Long getRefId() {
        return refId;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public Long getUserId() {
        return userId;
    }

    public long getPriceIdr() {
        return priceIdr;
    }

    public PayChannel getPayChannel() {
        return payChannel;
    }

    public Long getPaymentIntentId() {
        return paymentIntentId;
    }

    public KeepsakePurchaseStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}

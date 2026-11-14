package com.tailtopia.starterpack.domain;

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
 * 礼包购买行（V1.5.0 gift-pack Story 4-2 · 表 {@code starter_pack_purchases}，架构 §3.5）。
 *
 * <p>资金流水：{@code user_id} 不加 FK，注销按匿名化口径不删。对外只暴露 {@code public_token}。
 * 状态机见 {@link StarterPackPurchaseStatus}；{@code fulfilled_at} 非空 = 次数与名额已发放（D-24）。
 */
@Entity
@Table(name = "starter_pack_purchases")
public class StarterPackPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "tier", nullable = false, updatable = false)
    private int tier;

    @Column(name = "price", nullable = false, updatable = false)
    private long price;

    @Column(name = "separate_total", nullable = false, updatable = false)
    private long separateTotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_channel", nullable = false, updatable = false, length = 16)
    private PayChannel payChannel;

    @Column(name = "payment_intent_id")
    private Long paymentIntentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private StarterPackPurchaseStatus status;

    @Column(name = "idem_key", nullable = false, updatable = false, length = 160)
    private String idemKey;

    @Column(name = "pay_expires_at")
    private Instant payExpiresAt;

    @Column(name = "purchase_index")
    private Integer purchaseIndex;

    @Column(name = "credits_expire_at")
    private Instant creditsExpireAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot_outcome", length = 16)
    private StarterPackSlotOutcome slotOutcome;

    @Column(name = "slot_rental_id")
    private Long slotRentalId;

    @Column(name = "slot_expires_at")
    private Instant slotExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    protected StarterPackPurchase() {
    }

    /** 新单（PENDING）：价格与单买合计按下单时快照。 */
    public static StarterPackPurchase pending(String publicToken, long userId, StarterPackTier tier, long price,
            long separateTotal, PayChannel channel, String idemKey, Instant now) {
        StarterPackPurchase p = new StarterPackPurchase();
        p.publicToken = publicToken;
        p.userId = userId;
        p.tier = tier.number();
        p.price = price;
        p.separateTotal = separateTotal;
        p.payChannel = channel;
        p.idemKey = idemKey;
        p.status = StarterPackPurchaseStatus.PENDING;
        p.createdAt = now;
        return p;
    }

    /** QRIS：回填意图与付款窗截止。 */
    public void attachPaymentIntent(long intentId, Instant payExpiresAt) {
        this.paymentIntentId = intentId;
        this.payExpiresAt = payExpiresAt;
    }

    /** 置 PAID（扣币 / 到账；过窗后才到账也从 EXPIRED / FAILED 进入，D-24）。 */
    public void markPaid(Instant now, int purchaseIndex) {
        this.status = StarterPackPurchaseStatus.PAID;
        this.paidAt = now;
        this.purchaseIndex = purchaseIndex;
    }

    /** 付款窗已过 / 意图过期（只由 PENDING 进入）。 */
    public void markExpired() {
        if (status == StarterPackPurchaseStatus.PENDING) {
            this.status = StarterPackPurchaseStatus.EXPIRED;
        }
    }

    /** 出码 / 付款失败（只由 PENDING 进入）。 */
    public void markFailed() {
        if (status == StarterPackPurchaseStatus.PENDING) {
            this.status = StarterPackPurchaseStatus.FAILED;
        }
    }

    /** 发放完成。 */
    public void markFulfilled(Instant now, Instant creditsExpireAt, StarterPackSlotOutcome slotOutcome,
            Long slotRentalId, Instant slotExpiresAt) {
        this.fulfilledAt = now;
        this.creditsExpireAt = creditsExpireAt;
        this.slotOutcome = slotOutcome;
        this.slotRentalId = slotRentalId;
        this.slotExpiresAt = slotExpiresAt;
    }

    public boolean isPending() {
        return status == StarterPackPurchaseStatus.PENDING;
    }

    /** PENDING 且付款窗已过（{@code pay_expires_at <= now}；无窗的 PENDING 视为未过窗）。 */
    public boolean isPendingPastWindow(Instant now) {
        return isPending() && payExpiresAt != null && !payExpiresAt.isAfter(now);
    }

    public StarterPackTier tierEnum() {
        return StarterPackTier.of(tier).orElseThrow(() -> new IllegalStateException("unknown tier " + tier));
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Long getUserId() {
        return userId;
    }

    public int getTier() {
        return tier;
    }

    public long getPrice() {
        return price;
    }

    public long getSeparateTotal() {
        return separateTotal;
    }

    public PayChannel getPayChannel() {
        return payChannel;
    }

    public Long getPaymentIntentId() {
        return paymentIntentId;
    }

    public StarterPackPurchaseStatus getStatus() {
        return status;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public Instant getPayExpiresAt() {
        return payExpiresAt;
    }

    public Integer getPurchaseIndex() {
        return purchaseIndex;
    }

    public Instant getCreditsExpireAt() {
        return creditsExpireAt;
    }

    public StarterPackSlotOutcome getSlotOutcome() {
        return slotOutcome;
    }

    public Long getSlotRentalId() {
        return slotRentalId;
    }

    public Instant getSlotExpiresAt() {
        return slotExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }
}

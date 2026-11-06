package com.tailtopia.shop.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * 用户券（v1.4.2 shop Story 2.1，{@code user_coupons}，D-2）—— <b>只读映射</b>。
 *
 * <p>🔴 P-2「券状态只经 {@code CouponLedgerService}」的结构性保证：本类 {@link Immutable}、
 * <b>没有任何 setter / 状态修改方法</b>，读仓库也没有 {@code save}；发券与改状态全走账本里的条件语句
 * （{@code CouponLedgerSingleEntryGuardTest} 守卫）。
 *
 * <p>「已过期」不落库（D-2）：{@link #isExpiredAt} 现算；有效期区间左闭右开 {@code [validFrom, validUntil)}
 * （与 1.2 计价器 P27 一致），一律用服务端 now（P-6）。
 */
@Entity
@Immutable
@Table(name = "user_coupons")
public class UserCoupon {

    @Id
    private Long id;

    @Column(name = "public_token", nullable = false, length = 32)
    private String publicToken;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private UserCouponSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserCouponStatus status;

    @Column(name = "locked_order_id")
    private Long lockedOrderId;

    @Column(name = "used_order_id")
    private Long usedOrderId;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    @Column(name = "expiry_notified_at")
    private Instant expiryNotifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserCoupon() {
    }

    /** 可用：AVAILABLE 且 {@code now ∈ [validFrom, validUntil)}。 */
    public boolean isUsableAt(Instant now) {
        return status == UserCouponStatus.AVAILABLE && !now.isBefore(validFrom) && now.isBefore(validUntil);
    }

    /** 已过期（现算，D-2）：AVAILABLE 且 {@code validUntil ≤ now}。LOCKED / USED / VOIDED 不算过期。 */
    public boolean isExpiredAt(Instant now) {
        return status == UserCouponStatus.AVAILABLE && !now.isBefore(validUntil);
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

    public Long getCouponId() {
        return couponId;
    }

    public UserCouponSource getSource() {
        return source;
    }

    public UserCouponStatus getStatus() {
        return status;
    }

    public Long getLockedOrderId() {
        return lockedOrderId;
    }

    public Long getUsedOrderId() {
        return usedOrderId;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getExpiryNotifiedAt() {
        return expiryNotifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

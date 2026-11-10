package com.tailtopia.shop.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 兑换码（v1.4.2 shop Story 2.1，{@code shop_coupon_codes}，D-4）。
 *
 * <p>{@code code} 已大写规范化（{@code trim().toUpperCase(Locale.ROOT)}，{@code ^[A-Z0-9]{4,32}$}）。
 * 码本身是对外<b>输入</b>，不是对外标识；🔴 日志 / 审计摘要绝不记码明文。
 *
 * <p>通用码（{@code single_use=false}）一张码多人兑换，永不写 {@code redeemed_*}；
 * 一次性码的核销只经 {@code ShopCouponCodeRepository.redeemSingleUse} 条件更新。
 */
@Entity
@Table(name = "shop_coupon_codes")
public class ShopCouponCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_id", nullable = false, updatable = false)
    private Long couponId;

    @Column(name = "code", nullable = false, length = 32, updatable = false)
    private String code;

    @Column(name = "single_use", nullable = false, updatable = false)
    private boolean singleUse;

    @Column(name = "redeemed_by_user_id", insertable = false, updatable = false)
    private Long redeemedByUserId;

    @Column(name = "redeemed_at", insertable = false, updatable = false)
    private Instant redeemedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShopCouponCode() {
    }

    public static ShopCouponCode of(long couponId, String normalizedCode, boolean singleUse) {
        ShopCouponCode c = new ShopCouponCode();
        c.couponId = couponId;
        c.code = normalizedCode;
        c.singleUse = singleUse;
        return c;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCouponId() {
        return couponId;
    }

    public String getCode() {
        return code;
    }

    public boolean isSingleUse() {
        return singleUse;
    }

    public Long getRedeemedByUserId() {
        return redeemedByUserId;
    }

    public Instant getRedeemedAt() {
        return redeemedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

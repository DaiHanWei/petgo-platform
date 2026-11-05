package com.tailtopia.shop.coupon.dto;

import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** {@link CouponView} 工厂（v1.4.2 shop Story 2.2）：未领模板 {@link #claimable}、已领用户券 {@link #owned}。 */
public final class CouponViews {

    /** 快过期阈值（D-6 / G1「Segera berakhir」）：{@code validUntil − now ≤ 48h}。 */
    public static final Duration EXPIRING_SOON = Duration.ofHours(48);

    private CouponViews() {
    }

    /** 模板视图（首页 / 详情可领券）。{@code claimed} = 本人已达每人限领。 */
    public static CouponView claimable(ShopCoupon c, boolean claimed, Instant now) {
        boolean fixed = c.getValidMode() == CouponValidMode.FIXED;
        return new CouponView(
                c.getPublicToken(), null, c.getKind().name(), c.getNameId(), c.getNameEn(),
                c.getAmount(), c.getMinSpend(), c.getScopeType().name(),
                scopeCategories(c), scopeProductCount(c),
                fixed ? c.getValidStartAt() : null,
                fixed ? c.getValidEndAt() : null,
                fixed ? null : c.getValidDays(),
                false, claimed, c.getAcquireType() == CouponAcquireType.NEWBIE, false,
                null, false, null, null, null);
    }

    /** 用户券视图（不带使用订单信息）。 */
    public static CouponView owned(UserCoupon uc, ShopCoupon c, Instant now) {
        return owned(uc, c, now, false, null, null);
    }

    /**
     * 用户券视图。
     *
     * @param isNew          刚兑换成功（G6「Baru」）
     * @param usedOrderNo    USED 时使用订单的展示号（C-9），否则 null
     * @param usedOrderToken USED 时使用订单的 public_token（C-9），否则 null
     */
    public static CouponView owned(UserCoupon uc, ShopCoupon c, Instant now, boolean isNew,
            String usedOrderNo, String usedOrderToken) {
        boolean used = uc.getStatus() == UserCouponStatus.USED;
        return new CouponView(
                c.getPublicToken(), uc.getPublicToken(), c.getKind().name(), c.getNameId(), c.getNameEn(),
                c.getAmount(), c.getMinSpend(), c.getScopeType().name(),
                scopeCategories(c), scopeProductCount(c),
                uc.getValidFrom(), uc.getValidUntil(), null,
                expiringSoon(uc, now), true, uc.getSource() == UserCouponSource.NEWBIE, isNew,
                uc.getStatus().name(),
                uc.getStatus() == UserCouponStatus.LOCKED,
                used ? uc.getUpdatedAt() : null,
                used ? usedOrderNo : null,
                used ? usedOrderToken : null);
    }

    /** AVAILABLE、未过期且剩余 ≤ 48h（恰 48h 算快过期）。 */
    static boolean expiringSoon(UserCoupon uc, Instant now) {
        if (uc.getStatus() != UserCouponStatus.AVAILABLE || !now.isBefore(uc.getValidUntil())) {
            return false;
        }
        return Duration.between(now, uc.getValidUntil()).compareTo(EXPIRING_SOON) <= 0;
    }

    private static List<String> scopeCategories(ShopCoupon c) {
        return c.getScopeType() == CouponScopeType.CATEGORY ? c.getScopeValues() : List.of();
    }

    private static Integer scopeProductCount(ShopCoupon c) {
        return c.getScopeType() == CouponScopeType.PRODUCT ? c.getScopeValues().size() : null;
    }
}

package com.tailtopia.shop.coupon;

import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponStatus;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.ShopCouponCode;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** v1.4.2 shop Story 2.2 L0 测试夹具：构造券模板 / 用户券 / 兑换码（不碰 DB）。 */
public final class CouponFixtures {

    public static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");

    private CouponFixtures() {
    }

    /** 发放期 [NOW-1d, NOW+10d)、FIXED 有效期、ALL 范围、无总量上限、每人 1 张。 */
    public static ShopCoupon coupon(long id, CouponAcquireType type) {
        return coupon(id, type, CouponScopeType.ALL, List.of());
    }

    public static ShopCoupon coupon(long id, CouponAcquireType type, CouponScopeType scope, List<String> values) {
        ShopCoupon c = ShopCoupon.create("tpl" + id, CouponKind.DISCOUNT, "Diskon " + id, "Discount " + id,
                10_000, 50_000, scope, values, type,
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(10)),
                CouponValidMode.FIXED, NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(20)), null,
                null, 1);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    public static ShopCoupon withIssueStart(ShopCoupon c, Instant start) {
        ReflectionTestUtils.setField(c, "issueStartAt", start);
        return c;
    }

    public static ShopCoupon stopped(ShopCoupon c) {
        ReflectionTestUtils.setField(c, "status", CouponStatus.STOPPED);
        return c;
    }

    public static ShopCoupon quantity(ShopCoupon c, Integer total, int issued) {
        ReflectionTestUtils.setField(c, "totalQuantity", total);
        ReflectionTestUtils.setField(c, "issuedCount", issued);
        return c;
    }

    public static ShopCoupon perUser(ShopCoupon c, int limit) {
        ReflectionTestUtils.setField(c, "perUserLimit", limit);
        return c;
    }

    public static UserCoupon userCoupon(long id, long userId, ShopCoupon c, UserCouponSource source,
            UserCouponStatus status, Instant validUntil) {
        UserCoupon uc = BeanUtils.instantiateClass(UserCoupon.class);
        ReflectionTestUtils.setField(uc, "id", id);
        ReflectionTestUtils.setField(uc, "publicToken", "uc" + id);
        ReflectionTestUtils.setField(uc, "userId", userId);
        ReflectionTestUtils.setField(uc, "couponId", c.getId());
        ReflectionTestUtils.setField(uc, "source", source);
        ReflectionTestUtils.setField(uc, "status", status);
        ReflectionTestUtils.setField(uc, "validFrom", NOW.minus(Duration.ofDays(1)));
        ReflectionTestUtils.setField(uc, "validUntil", validUntil);
        ReflectionTestUtils.setField(uc, "claimedAt", NOW.minus(Duration.ofHours(id)));
        ReflectionTestUtils.setField(uc, "createdAt", NOW.minus(Duration.ofHours(id)));
        ReflectionTestUtils.setField(uc, "updatedAt", NOW.minus(Duration.ofMinutes(id)));
        return uc;
    }

    public static UserCoupon usedBy(UserCoupon uc, long orderId) {
        ReflectionTestUtils.setField(uc, "usedOrderId", orderId);
        return uc;
    }

    public static ShopCouponCode code(long id, long couponId, String code, boolean singleUse, Instant redeemedAt) {
        ShopCouponCode row = ShopCouponCode.of(couponId, code, singleUse);
        ReflectionTestUtils.setField(row, "id", id);
        ReflectionTestUtils.setField(row, "redeemedAt", redeemedAt);
        return row;
    }
}

package com.tailtopia.shop.order.pricing;

/**
 * 计价器输入：选券方式（Story 1.2，FR-130.3）。
 *
 * <p>AUTO：默认最优组合（贪心口径，S-10）；MANUAL：只用指定 token（null = 不用该类券）。
 */
public record CouponSelection(Mode mode, String discountUserCouponToken,
        String freeShippingUserCouponToken) {

    public enum Mode {
        AUTO,
        MANUAL
    }

    public static CouponSelection auto() {
        return new CouponSelection(Mode.AUTO, null, null);
    }

    public static CouponSelection manual(String discountUserCouponToken,
            String freeShippingUserCouponToken) {
        return new CouponSelection(Mode.MANUAL, discountUserCouponToken, freeShippingUserCouponToken);
    }
}

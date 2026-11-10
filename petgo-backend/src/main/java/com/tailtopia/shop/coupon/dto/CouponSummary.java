package com.tailtopia.shop.coupon.dto;

/** {@code GET /me/coupons/summary}（Story 2.2 AC8.4，9.1 入口计数）。口径同 {@link CouponPage#availableCount()}。 */
public record CouponSummary(long availableCount) {
}

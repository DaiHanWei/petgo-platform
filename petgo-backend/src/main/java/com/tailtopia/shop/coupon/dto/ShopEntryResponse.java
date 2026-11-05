package com.tailtopia.shop.coupon.dto;

/** {@code POST /me/shop/entry}（Story 2.2 AC4）：本次进入是否发放了新人券（true 时 App 上报埋点，8.1）。 */
public record ShopEntryResponse(boolean newbieCouponIssued) {
}

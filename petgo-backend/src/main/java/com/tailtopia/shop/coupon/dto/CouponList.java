package com.tailtopia.shop.coupon.dto;

import java.util.List;

/** {@code {items: CouponView[]}}（首页「给你的券」/ 商品可领券，Story 2.2 AC6 / AC7）。无则空数组。 */
public record CouponList(List<CouponView> items) {
}

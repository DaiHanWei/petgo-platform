package com.tailtopia.shop.coupon.domain;

/**
 * 券类型（v1.4.2 shop Story 2.1，D-1）：满减券 / 免运券（{@code amount} 为每张减免封顶）。
 *
 * <p>落库 varchar + CHECK，UPPER_SNAKE。🔴 只在末尾追加，不重排 / 不删除 / 不改既有值拼写。
 */
public enum CouponKind {
    DISCOUNT, FREE_SHIPPING
}

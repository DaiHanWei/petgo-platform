package com.tailtopia.shop.coupon.domain;

/**
 * 用户券状态（D-2）。🔴 「已过期」不落库，由 {@code valid_until} 现算；{@code VOIDED} 用户端不展示、不通知。状态只经 {@code CouponLedgerService} 改（P-2）。
 *
 * <p>落库 varchar + CHECK，UPPER_SNAKE。🔴 只在末尾追加，不重排 / 不删除 / 不改既有值拼写。
 */
public enum UserCouponStatus {
    AVAILABLE, LOCKED, USED, VOIDED
}

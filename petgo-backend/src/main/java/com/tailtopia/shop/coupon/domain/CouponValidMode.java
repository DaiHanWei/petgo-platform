package com.tailtopia.shop.coupon.domain;

/**
 * 使用有效期模式（D-1）：固定起止 / 领取后 N 天（到期取领取当天 WIB + N 天的 23:59:59 WIB）。
 *
 * <p>落库 varchar + CHECK，UPPER_SNAKE。🔴 只在末尾追加，不重排 / 不删除 / 不改既有值拼写。
 */
public enum CouponValidMode {
    FIXED, RELATIVE
}

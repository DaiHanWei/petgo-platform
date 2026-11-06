package com.tailtopia.shop.coupon.domain;

/**
 * 券模板状态（D-1）。停用后不能再领 / 兑 / 发；已领未用的是否作废看 {@code void_on_stop}（D-3）。
 *
 * <p>落库 varchar + CHECK，UPPER_SNAKE。🔴 只在末尾追加，不重排 / 不删除 / 不改既有值拼写。
 */
public enum CouponStatus {
    ACTIVE, STOPPED
}

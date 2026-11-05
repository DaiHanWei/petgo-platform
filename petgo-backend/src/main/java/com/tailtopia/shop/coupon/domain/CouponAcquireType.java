package com.tailtopia.shop.coupon.domain;

/**
 * 券的获取方式（D-1）：新人自动发放 / 主动领取 / 兑换码。
 *
 * <p>落库 varchar + CHECK，UPPER_SNAKE。🔴 只在末尾追加，不重排 / 不删除 / 不改既有值拼写。
 */
public enum CouponAcquireType {
    NEWBIE, CLAIM, CODE
}

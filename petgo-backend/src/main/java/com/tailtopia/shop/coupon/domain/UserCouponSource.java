package com.tailtopia.shop.coupon.domain;

/**
 * 用户券来源（D-2）。{@code REISSUE} 为迟到款重下单（4.2）预留。
 *
 * <p>落库 varchar + CHECK，UPPER_SNAKE。🔴 只在末尾追加，不重排 / 不删除 / 不改既有值拼写。
 */
public enum UserCouponSource {
    NEWBIE, CLAIM, CODE, BANNER, REISSUE
}

package com.tailtopia.shop.coupon.domain;

/**
 * 券的适用范围（D-1）：全场 / 指定品类（{@code ProductCategory} 名）/ 指定商品（商品 public_token）。
 *
 * <p>落库 varchar + CHECK，UPPER_SNAKE。🔴 只在末尾追加，不重排 / 不删除 / 不改既有值拼写。
 */
public enum CouponScopeType {
    ALL, CATEGORY, PRODUCT
}

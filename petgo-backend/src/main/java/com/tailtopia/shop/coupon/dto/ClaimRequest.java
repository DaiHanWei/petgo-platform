package com.tailtopia.shop.coupon.dto;

/** 领券请求（Story 2.2 AC2）：{@code source} 取 {@code HOME | DETAIL | BANNER}，大小写不敏感。 */
public record ClaimRequest(String couponToken, String source) {
}

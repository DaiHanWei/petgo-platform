package com.tailtopia.shop.coupon.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import java.util.Locale;

/** 领券入口（Story 2.2 AC2）：首页 / 详情 / banner。落库来源：BANNER → {@code BANNER}，其余 → {@code CLAIM}。 */
public enum CouponClaimSource {
    HOME, DETAIL, BANNER;

    /** 大小写不敏感；空或非法 → 422。 */
    public static CouponClaimSource parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AppException.validation("领券来源不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw AppException.validation("领券来源非法");
        }
    }

    public UserCouponSource ledgerSource() {
        return this == BANNER ? UserCouponSource.BANNER : UserCouponSource.CLAIM;
    }
}

package com.tailtopia.shop.coupon.service;

import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import java.time.Instant;
import java.util.List;

/**
 * 建券入参（v1.4.2 shop Story 2.1 AC3，后台线 Controller 与 D-24 stag 入口共用）。
 *
 * <p>只是数据载体，校验全在 {@link CouponAdminService#create}。可空字段的默认值：
 * {@code minSpend} null → 0（无门槛）；{@code scopeType} null → ALL；{@code scopeValues} null → 空；
 * {@code perUserLimit} null → 1；{@code totalQuantity} null → 不限。
 *
 * @param amount 满减额；免运券 = 每张减免封顶（印尼盾整数）
 */
public record CouponDraft(
        CouponKind kind,
        String nameId,
        String nameEn,
        Long amount,
        Long minSpend,
        CouponScopeType scopeType,
        List<String> scopeValues,
        CouponAcquireType acquireType,
        Instant issueStartAt,
        Instant issueEndAt,
        CouponValidMode validMode,
        Instant validStartAt,
        Instant validEndAt,
        Integer validDays,
        Integer totalQuantity,
        Integer perUserLimit) {
}

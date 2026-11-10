package com.tailtopia.shop.coupon.service;

import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.domain.ProductCategory;

/**
 * 券模板是否覆盖某商品（v1.4.2 shop Story 2.2，D-6）——商品可领券、{@code PromoResolver}、3.1 券分组共用，
 * 避免各写一遍。判据与 1.2 计价器 {@code ShopPricingCalculator.inScope} 同口径：
 * {@code ALL} 恒覆盖；{@code CATEGORY} 看品类码；{@code PRODUCT} 看商品 public_token。
 */
public final class CouponScopeMatcher {

    private CouponScopeMatcher() {
    }

    public static boolean covers(ShopCoupon c, String productToken, ProductCategory category) {
        CouponScopeType type = c.getScopeType() == null ? CouponScopeType.ALL : c.getScopeType();
        return switch (type) {
            case ALL -> true;
            case CATEGORY -> category != null && c.getScopeValues().contains(category.name());
            case PRODUCT -> productToken != null && c.getScopeValues().contains(productToken);
        };
    }
}

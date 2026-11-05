package com.tailtopia.shop.coupon.service;

import static com.tailtopia.shop.coupon.CouponFixtures.coupon;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.domain.ProductCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 2.2 —— 券范围匹配（ALL / CATEGORY / PRODUCT）。 */
class CouponScopeMatcherTest {

    @Test
    void allCoversAnyProduct() {
        ShopCoupon c = coupon(1L, CouponAcquireType.CLAIM);
        assertThat(CouponScopeMatcher.covers(c, "x", ProductCategory.MAKANAN)).isTrue();
        assertThat(CouponScopeMatcher.covers(c, "y", null)).isTrue();
    }

    @Test
    void categoryMatchesByCategoryCode() {
        ShopCoupon c = coupon(1L, CouponAcquireType.CLAIM, CouponScopeType.CATEGORY, List.of("MAKANAN", "CAMILAN"));
        assertThat(CouponScopeMatcher.covers(c, "x", ProductCategory.CAMILAN)).isTrue();
        assertThat(CouponScopeMatcher.covers(c, "x", ProductCategory.PERAWATAN)).isFalse();
        assertThat(CouponScopeMatcher.covers(c, "x", null)).isFalse();
    }

    @Test
    void productMatchesByToken() {
        ShopCoupon c = coupon(1L, CouponAcquireType.CLAIM, CouponScopeType.PRODUCT, List.of("p1"));
        assertThat(CouponScopeMatcher.covers(c, "p1", ProductCategory.MAKANAN)).isTrue();
        assertThat(CouponScopeMatcher.covers(c, "p2", ProductCategory.MAKANAN)).isFalse();
        assertThat(CouponScopeMatcher.covers(c, null, ProductCategory.MAKANAN)).isFalse();
    }
}

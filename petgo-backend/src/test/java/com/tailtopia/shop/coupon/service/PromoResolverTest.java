package com.tailtopia.shop.coupon.service;

import static com.tailtopia.shop.coupon.CouponFixtures.NOW;
import static com.tailtopia.shop.coupon.CouponFixtures.coupon;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.service.PromoResolver.ProductRef;
import com.tailtopia.shop.domain.ProductCategory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 2.2 AC9 —— promo 三种覆盖、用户券只对本人生效、查询次数与商品数无关。 */
class PromoResolverTest {

    private ShopCouponRepository coupons;
    private PromoResolver resolver;

    private final List<ProductRef> refs = List.of(
            new ProductRef("p1", ProductCategory.MAKANAN),
            new ProductRef("p2", ProductCategory.CAMILAN),
            new ProductRef("p3", ProductCategory.PERAWATAN));

    @BeforeEach
    void setUp() {
        coupons = mock(ShopCouponRepository.class);
        resolver = new PromoResolver(coupons);
    }

    @Test
    void allScopeCoversEverything() {
        when(coupons.findIssuable(CouponAcquireType.CLAIM, NOW)).thenReturn(List.of(coupon(1L, CouponAcquireType.CLAIM)));
        assertThat(resolver.resolve(refs, null, NOW)).containsExactlyInAnyOrder("p1", "p2", "p3");
    }

    @Test
    void categoryAndProductScopes() {
        when(coupons.findIssuable(CouponAcquireType.CLAIM, NOW)).thenReturn(List.of(
                coupon(1L, CouponAcquireType.CLAIM, CouponScopeType.CATEGORY, List.of("CAMILAN")),
                coupon(2L, CouponAcquireType.CLAIM, CouponScopeType.PRODUCT, List.of("p3"))));
        assertThat(resolver.resolve(refs, null, NOW)).containsExactlyInAnyOrder("p2", "p3");
    }

    @Test
    void heldCouponOnlyCountsForThatUser() {
        when(coupons.findHeldAvailableByUser(5L, NOW)).thenReturn(List.of(
                coupon(9L, CouponAcquireType.NEWBIE, CouponScopeType.PRODUCT, List.of("p1"))));
        assertThat(resolver.resolve(refs, null, NOW)).isEmpty();
        assertThat(resolver.resolve(refs, 5L, NOW)).containsExactly("p1");
        verify(coupons, times(1)).findHeldAvailableByUser(5L, NOW);
    }

    @Test
    void queryCountIndependentOfProductCount() {
        List<ProductRef> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new ProductRef("p" + i, ProductCategory.MAKANAN));
        }
        resolver.resolve(many, 5L, NOW);
        verify(coupons, times(1)).findIssuable(CouponAcquireType.CLAIM, NOW);
        verify(coupons, times(1)).findHeldAvailableByUser(5L, NOW);
        verifyNoMoreInteractions(coupons);
    }

    @Test
    void guestSkipsHeldQueryAndEmptyInputQueriesNothing() {
        resolver.resolve(refs, null, NOW);
        verify(coupons, never()).findHeldAvailableByUser(anyLong(), any());
        PromoResolver fresh = new PromoResolver(mock(ShopCouponRepository.class));
        assertThat(fresh.resolve(List.of(), 5L, NOW)).isEmpty();
    }
}

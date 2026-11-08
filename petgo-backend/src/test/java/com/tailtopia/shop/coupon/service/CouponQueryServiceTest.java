package com.tailtopia.shop.coupon.service;

import static com.tailtopia.shop.coupon.CouponFixtures.NOW;
import static com.tailtopia.shop.coupon.CouponFixtures.coupon;
import static com.tailtopia.shop.coupon.CouponFixtures.userCoupon;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.coupon.CouponFixtures;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import com.tailtopia.shop.coupon.dto.CouponPage;
import com.tailtopia.shop.coupon.dto.CouponView;
import com.tailtopia.shop.coupon.dto.CouponViews;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 2.2 AC6 ~ AC8 / C-3 / C-9 —— 首页券排序、商品可领券、我的券。 */
class CouponQueryServiceTest {

    private static final long UID = 9L;

    private ShopCouponRepository coupons;
    private UserCouponRepository userCoupons;
    private ShopProductRepository products;
    private ShopOrderRepository orders;
    private CouponQueryService service;

    @BeforeEach
    void setUp() {
        coupons = mock(ShopCouponRepository.class);
        userCoupons = mock(UserCouponRepository.class);
        products = mock(ShopProductRepository.class);
        orders = mock(ShopOrderRepository.class);
        service = new CouponQueryService(coupons, userCoupons, products, orders);
    }

    private static Object[] row(long couponId, long n) {
        return new Object[] {couponId, n};
    }

    @Test
    void forYouOrdering() {
        ShopCoupon newbieTpl = coupon(1L, CouponAcquireType.NEWBIE);
        UserCoupon newbieUc = userCoupon(50L, UID, newbieTpl, UserCouponSource.NEWBIE, UserCouponStatus.AVAILABLE,
                NOW.plus(Duration.ofDays(5)));
        when(userCoupons.findUsableBySource(UID, UserCouponSource.NEWBIE, NOW)).thenReturn(List.of(newbieUc));
        when(coupons.findAllById(List.of(1L))).thenReturn(List.of(newbieTpl));

        // 仓储按 issue_start_at 倒序：10（已领）、11（未领）、12（未领但领完 → 不出现）、13（已领且领完 → 仍出现 claimed）
        ShopCoupon c10 = coupon(10L, CouponAcquireType.CLAIM);
        ShopCoupon c11 = coupon(11L, CouponAcquireType.CLAIM);
        ShopCoupon c12 = CouponFixtures.quantity(coupon(12L, CouponAcquireType.CLAIM), 3, 3);
        ShopCoupon c13 = CouponFixtures.quantity(coupon(13L, CouponAcquireType.CLAIM), 3, 3);
        ShopCoupon c14 = coupon(14L, CouponAcquireType.CLAIM);
        when(coupons.findIssuable(CouponAcquireType.CLAIM, NOW)).thenReturn(List.of(c10, c11, c12, c13, c14));
        when(userCoupons.countByUserGroupedByCoupon(eq(UID), anyCollection()))
                .thenReturn(List.of(row(10L, 1L), row(13L, 1L)));

        List<CouponView> out = service.forYou(UID, NOW);
        assertThat(out).extracting(CouponView::couponToken)
                .containsExactly("tpl1", "tpl11", "tpl14", "tpl10", "tpl13");
        assertThat(out.get(0).newbie()).isTrue();
        assertThat(out.get(0).claimed()).isTrue();
        assertThat(out).filteredOn(v -> v.couponToken().equals("tpl11")).first()
                .satisfies(v -> {
                    assertThat(v.claimed()).isFalse();
                    assertThat(v.userCouponToken()).isNull();
                });
        assertThat(out).filteredOn(v -> v.couponToken().equals("tpl10")).first()
                .satisfies(v -> assertThat(v.claimed()).isTrue());
    }

    @Test
    void forYouRespectsPerUserLimitAboveOne() {
        ShopCoupon c = CouponFixtures.perUser(coupon(10L, CouponAcquireType.CLAIM), 2);
        when(coupons.findIssuable(CouponAcquireType.CLAIM, NOW)).thenReturn(List.of(c));
        when(userCoupons.countByUserGroupedByCoupon(eq(UID), anyCollection())).thenReturn(List.<Object[]>of(row(10L, 1L)));
        assertThat(service.forYou(UID, NOW)).singleElement().satisfies(v -> assertThat(v.claimed()).isFalse());
    }

    @Test
    void forYouQueriesOnlyClaimTemplatesAndCapsAt20() {
        List<ShopCoupon> many = new ArrayList<>();
        for (long i = 100; i < 130; i++) {
            many.add(coupon(i, CouponAcquireType.CLAIM));
        }
        when(coupons.findIssuable(CouponAcquireType.CLAIM, NOW)).thenReturn(many);
        assertThat(service.forYou(UID, NOW)).hasSize(20);
        // 只查 CLAIM 类（CODE 类模板只能兑换，从不进首页）
        org.mockito.Mockito.verify(coupons, org.mockito.Mockito.never()).findIssuable(eq(CouponAcquireType.CODE), any());
    }

    @Test
    void forYouEmptyIsEmptyList() {
        assertThat(service.forYou(UID, NOW)).isEmpty();
    }

    private ShopProduct product(String token, ProductCategory category) {
        ShopProduct p = BeanUtils.instantiateClass(ShopProduct.class);
        ReflectionTestUtils.setField(p, "publicToken", token);
        ReflectionTestUtils.setField(p, "category", category);
        return p;
    }

    @Test
    void productCouponsFilterByScopeAndSoldOut() {
        when(products.findByPublicTokenAndActiveTrue("p1")).thenReturn(Optional.of(product("p1", ProductCategory.MAKANAN)));
        ShopCoupon all = coupon(1L, CouponAcquireType.CLAIM);
        ShopCoupon cat = coupon(2L, CouponAcquireType.CLAIM, CouponScopeType.CATEGORY, List.of("CAMILAN"));
        ShopCoupon prod = coupon(3L, CouponAcquireType.CLAIM, CouponScopeType.PRODUCT, List.of("p1"));
        ShopCoupon soldOut = CouponFixtures.quantity(coupon(4L, CouponAcquireType.CLAIM), 1, 1);
        ShopCoupon soldOutButMine = CouponFixtures.quantity(coupon(5L, CouponAcquireType.CLAIM), 1, 1);
        when(coupons.findIssuable(CouponAcquireType.CLAIM, NOW)).thenReturn(List.of(all, cat, prod, soldOut, soldOutButMine));
        when(userCoupons.countByUserGroupedByCoupon(eq(UID), anyCollection())).thenReturn(List.<Object[]>of(row(5L, 1L)));

        List<CouponView> mine = service.forProduct("p1", UID, NOW);
        assertThat(mine).extracting(CouponView::couponToken).containsExactly("tpl1", "tpl3", "tpl5");
        assertThat(mine.get(2).claimed()).isTrue();

        List<CouponView> guest = service.forProduct("p1", null, NOW);
        assertThat(guest).extracting(CouponView::couponToken).containsExactly("tpl1", "tpl3");
        assertThat(guest).allSatisfy(v -> assertThat(v.claimed()).isFalse());
    }

    @Test
    void productCouponsSoldOutWithPartialHoldingIsClaimed() {
        // 复审修复：每人限领 2、本人领了 1、已领完 → 不能再给「Klaim」（点了必 sold-out），按 claimed=true
        when(products.findByPublicTokenAndActiveTrue("p1")).thenReturn(Optional.of(product("p1", ProductCategory.MAKANAN)));
        ShopCoupon partial = CouponFixtures.perUser(CouponFixtures.quantity(coupon(6L, CouponAcquireType.CLAIM), 2, 2), 2);
        ShopCoupon openPartial = CouponFixtures.perUser(coupon(7L, CouponAcquireType.CLAIM), 2);
        ShopCoupon soldOutNotMine = CouponFixtures.perUser(CouponFixtures.quantity(coupon(8L, CouponAcquireType.CLAIM), 2, 2), 2);
        when(coupons.findIssuable(CouponAcquireType.CLAIM, NOW)).thenReturn(List.of(partial, openPartial, soldOutNotMine));
        when(userCoupons.countByUserGroupedByCoupon(eq(UID), anyCollection()))
                .thenReturn(List.<Object[]>of(row(6L, 1L), row(7L, 1L)));

        List<CouponView> out = service.forProduct("p1", UID, NOW);
        assertThat(out).extracting(CouponView::couponToken).containsExactly("tpl6", "tpl7");
        assertThat(out.get(0).claimed()).isTrue();
        assertThat(out.get(1).claimed()).isFalse();
    }

    @Test
    void forYouSoldOutWithPartialHoldingIsClaimed() {
        ShopCoupon partial = CouponFixtures.perUser(CouponFixtures.quantity(coupon(6L, CouponAcquireType.CLAIM), 2, 2), 2);
        when(coupons.findIssuable(CouponAcquireType.CLAIM, NOW)).thenReturn(List.of(partial));
        when(userCoupons.countByUserGroupedByCoupon(eq(UID), anyCollection())).thenReturn(List.<Object[]>of(row(6L, 1L)));
        assertThat(service.forYou(UID, NOW)).singleElement().satisfies(v -> assertThat(v.claimed()).isTrue());
    }

    @Test
    void productCouponsForMissingOrDelistedProductIsEmpty() {
        when(products.findByPublicTokenAndActiveTrue("gone")).thenReturn(Optional.empty());
        assertThat(service.forProduct("gone", UID, NOW)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(coupons);
    }

    @Test
    void myAvailableShowsLockedGreyedAndCountsOnlyUsable() {
        ShopCoupon c = coupon(1L, CouponAcquireType.CLAIM);
        UserCoupon avail = userCoupon(1L, UID, c, UserCouponSource.CLAIM, UserCouponStatus.AVAILABLE, NOW.plus(Duration.ofDays(5)));
        UserCoupon locked = userCoupon(2L, UID, c, UserCouponSource.CLAIM, UserCouponStatus.LOCKED, NOW.plus(Duration.ofDays(5)));
        when(userCoupons.pageAvailable(eq(UID), eq(NOW), any(), anyLong(), any())).thenReturn(List.of(avail, locked));
        when(coupons.findAllById(List.of(1L))).thenReturn(List.of(c));
        when(userCoupons.countUsable(UID, NOW)).thenReturn(1L);

        CouponPage page = service.myCoupons(UID, CouponQueryService.Tab.AVAILABLE, null, NOW);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).lockedByPendingOrder()).isFalse();
        assertThat(page.items().get(1).lockedByPendingOrder()).isTrue();
        assertThat(page.items().get(1).status()).isEqualTo("LOCKED");
        assertThat(page.availableCount()).isEqualTo(1L);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void myAvailablePagesBy20WithCursor() {
        ShopCoupon c = coupon(1L, CouponAcquireType.CLAIM);
        List<UserCoupon> rows = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            rows.add(userCoupon(i, UID, c, UserCouponSource.CLAIM, UserCouponStatus.AVAILABLE, NOW.plus(Duration.ofDays(5))));
        }
        when(userCoupons.pageAvailable(eq(UID), eq(NOW), any(), anyLong(), any())).thenReturn(rows);
        when(coupons.findAllById(List.of(1L))).thenReturn(List.of(c));
        CouponPage page = service.myCoupons(UID, CouponQueryService.Tab.AVAILABLE, "garbage!!", NOW);
        assertThat(page.items()).hasSize(20);
        com.tailtopia.shared.paging.KeysetCursor next = com.tailtopia.shared.paging.KeysetCursor.decodeOrNull(page.nextCursor());
        assertThat(next).isNotNull();
        assertThat(next.id()).isEqualTo(20L);
        assertThat(next.createdAt()).isEqualTo(rows.get(19).getClaimedAt());
    }

    @Test
    void myUsedCarriesUsedOrderInfo() {
        ShopCoupon c = coupon(1L, CouponAcquireType.CLAIM);
        UserCoupon used = CouponFixtures.usedBy(
                userCoupon(3L, UID, c, UserCouponSource.CLAIM, UserCouponStatus.USED, NOW.plus(Duration.ofDays(5))), 77L);
        when(userCoupons.pageUsed(eq(UID), any(), anyLong(), any())).thenReturn(List.of(used));
        when(coupons.findAllById(List.of(1L))).thenReturn(List.of(c));
        ShopOrder o = BeanUtils.instantiateClass(ShopOrder.class);
        ReflectionTestUtils.setField(o, "id", 77L);
        ReflectionTestUtils.setField(o, "publicToken", "ord77");
        ReflectionTestUtils.setField(o, "displayNo", "TT-77");
        when(orders.findAllById(List.of(77L))).thenReturn(List.of(o));

        CouponView v = service.myCoupons(UID, CouponQueryService.Tab.USED, null, NOW).items().get(0);
        assertThat(v.status()).isEqualTo("USED");
        assertThat(v.usedAt()).isEqualTo(used.getUpdatedAt());
        assertThat(v.usedOrderNo()).isEqualTo("TT-77");
        assertThat(v.usedOrderToken()).isEqualTo("ord77");
        assertThat(v.expiringSoon()).isFalse();
    }

    @Test
    void expiringSoonBoundaryIs48HoursInclusive() {
        ShopCoupon c = coupon(1L, CouponAcquireType.CLAIM);
        UserCoupon exact = userCoupon(1L, UID, c, UserCouponSource.CLAIM, UserCouponStatus.AVAILABLE, NOW.plus(Duration.ofHours(48)));
        UserCoupon over = userCoupon(2L, UID, c, UserCouponSource.CLAIM, UserCouponStatus.AVAILABLE,
                NOW.plus(Duration.ofHours(48)).plusSeconds(1));
        assertThat(CouponViews.owned(exact, c, NOW).expiringSoon()).isTrue();
        assertThat(CouponViews.owned(over, c, NOW).expiringSoon()).isFalse();
        UserCoupon locked = userCoupon(3L, UID, c, UserCouponSource.CLAIM, UserCouponStatus.LOCKED, NOW.plus(Duration.ofHours(1)));
        assertThat(CouponViews.owned(locked, c, NOW).expiringSoon()).isFalse();
    }

    @Test
    void tabParsing() {
        assertThat(CouponQueryService.Tab.parse(null)).isEqualTo(CouponQueryService.Tab.AVAILABLE);
        assertThat(CouponQueryService.Tab.parse("used")).isEqualTo(CouponQueryService.Tab.USED);
        assertThatThrownBy(() -> CouponQueryService.Tab.parse("expired")).isInstanceOf(AppException.class);
    }
}

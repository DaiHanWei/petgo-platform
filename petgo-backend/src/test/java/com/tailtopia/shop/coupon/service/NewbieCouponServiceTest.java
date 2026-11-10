package com.tailtopia.shop.coupon.service;

import static com.tailtopia.shop.coupon.CouponFixtures.NOW;
import static com.tailtopia.shop.coupon.CouponFixtures.coupon;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.shop.coupon.CouponFixtures;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 2.2 AC4 / AC5 —— 新人券判定与幂等。 */
class NewbieCouponServiceTest {

    private static final long UID = 7L;

    private ShopCouponRepository coupons;
    private UserCouponRepository userCoupons;
    private CouponLedgerService ledger;
    private AccountQueryService accounts;
    private ShopOrderRepository orders;
    private NewbieCouponService service;
    private CouponRowLock rowLock;
    private ShopCoupon newbie;

    @BeforeEach
    void setUp() {
        coupons = mock(ShopCouponRepository.class);
        userCoupons = mock(UserCouponRepository.class);
        ledger = mock(CouponLedgerService.class);
        accounts = mock(AccountQueryService.class);
        orders = mock(ShopOrderRepository.class);
        rowLock = mock(CouponRowLock.class);
        service = new NewbieCouponService(coupons, userCoupons, ledger, accounts, orders, rowLock);

        newbie = coupon(3L, CouponAcquireType.NEWBIE);
        when(coupons.findIssuable(CouponAcquireType.NEWBIE, NOW)).thenReturn(List.of(newbie));
        when(rowLock.lockFresh(3L)).thenReturn(Optional.of(newbie));
        when(coupons.incrementIssuedIfAvailable(3L)).thenReturn(1);
        when(accounts.isActiveRegularUser(UID)).thenReturn(true);
        when(accounts.createdAtOf(UID)).thenReturn(Optional.of(NOW.minus(Duration.ofDays(3))));
    }

    @Test
    void noCampaignIsFalse() {
        when(coupons.findIssuable(CouponAcquireType.NEWBIE, NOW)).thenReturn(List.of());
        assertThat(service.onEntry(UID, NOW)).isFalse();
        verify(accounts, never()).isActiveRegularUser(anyLong());
    }

    @Test
    void nonRegularUserIsFalse() {
        // isActiveRegularUser 覆盖：非 USER 角色 / VIRTUAL 马甲 / 已注销 / 封号
        when(accounts.isActiveRegularUser(UID)).thenReturn(false);
        assertThat(service.onEntry(UID, NOW)).isFalse();
        verify(ledger, never()).issue(anyLong(), any(), any(), any());
    }

    @Test
    void registeredExactly30DaysAgoIsNotNewbie() {
        when(accounts.createdAtOf(UID)).thenReturn(Optional.of(NOW.minus(Duration.ofDays(30))));
        assertThat(service.onEntry(UID, NOW)).isFalse();
    }

    @Test
    void registered29Days23HoursAgoIsNewbie() {
        when(accounts.createdAtOf(UID)).thenReturn(Optional.of(NOW.minus(Duration.ofDays(29)).minus(Duration.ofHours(23))));
        assertThat(service.onEntry(UID, NOW)).isTrue();
    }

    @Test
    void paidOrderDisqualifies() {
        when(orders.existsByUserIdAndPaidAtIsNotNull(UID)).thenReturn(true);
        assertThat(service.onEntry(UID, NOW)).isFalse();
        verify(rowLock, never()).lockFresh(anyLong());
    }

    @Test
    void onlyUnpaidOrdersStillNewbie() {
        // 仓库只看 paid_at 非空：未付 / 超时取消的订单不影响
        when(orders.existsByUserIdAndPaidAtIsNotNull(UID)).thenReturn(false);
        assertThat(service.onEntry(UID, NOW)).isTrue();
    }

    @Test
    void alreadyHoldingIsIdempotentFalse() {
        when(userCoupons.existsByUserIdAndCouponId(UID, 3L)).thenReturn(true);
        assertThat(service.onEntry(UID, NOW)).isFalse();
        verify(coupons, never()).incrementIssuedIfAvailable(any());
        verify(ledger, never()).issue(anyLong(), any(), any(), any());
    }

    @Test
    void soldOutIsSilentFalse() {
        when(coupons.incrementIssuedIfAvailable(3L)).thenReturn(0);
        assertThat(service.onEntry(UID, NOW)).isFalse();
        verify(ledger, never()).issue(anyLong(), any(), any(), any());
    }

    @Test
    void issuesWithNewbieSource() {
        assertThat(service.onEntry(UID, NOW)).isTrue();
        verify(rowLock).lockFresh(3L);
        verify(ledger).issue(UID, newbie, UserCouponSource.NEWBIE, NOW);
    }

    @Test
    void stoppedAfterRefreshUnderLockIsFalse() {
        // 复审修复：加锁后读到的是刷新后的状态（此处模拟锁内发现已停用）
        ShopCoupon fresh = CouponFixtures.stopped(coupon(3L, CouponAcquireType.NEWBIE));
        when(rowLock.lockFresh(3L)).thenReturn(Optional.of(fresh));
        assertThat(service.onEntry(UID, NOW)).isFalse();
        verify(coupons, never()).incrementIssuedIfAvailable(any());
    }

    @Test
    void multipleCampaignsTakesLatestStart() {
        ShopCoupon later = CouponFixtures.withIssueStart(coupon(4L, CouponAcquireType.NEWBIE), NOW.minusSeconds(10));
        // 仓储按 issue_start_at 倒序返回
        when(coupons.findIssuable(CouponAcquireType.NEWBIE, NOW)).thenReturn(List.of(later, newbie));
        assertThat(service.currentCampaign(NOW)).contains(later);
    }

    @Test
    void guestCampaignHiddenWhenSoldOut() {
        assertThat(service.guestCampaign(NOW)).contains(newbie);
        CouponFixtures.quantity(newbie, 5, 5);
        assertThat(service.guestCampaign(NOW)).isEmpty();
        Instant later = NOW;
        when(coupons.findIssuable(CouponAcquireType.NEWBIE, later)).thenReturn(List.of());
        assertThat(service.guestCampaign(later)).isEmpty();
    }
}

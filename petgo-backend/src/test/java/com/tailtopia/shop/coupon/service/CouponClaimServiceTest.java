package com.tailtopia.shop.coupon.service;

import static com.tailtopia.shop.coupon.CouponFixtures.NOW;
import static com.tailtopia.shop.coupon.CouponFixtures.coupon;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.coupon.CouponFixtures;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import com.tailtopia.shop.coupon.dto.CouponView;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** L0：v1.4.2 shop Story 2.2 AC2 —— 领券判序与来源映射。 */
class CouponClaimServiceTest {

    private ShopCouponRepository coupons;
    private UserCouponRepository userCoupons;
    private CouponLedgerService ledger;
    private CouponRowLock rowLock;
    private CouponClaimService service;

    @BeforeEach
    void setUp() {
        coupons = mock(ShopCouponRepository.class);
        userCoupons = mock(UserCouponRepository.class);
        ledger = mock(CouponLedgerService.class);
        rowLock = mock(CouponRowLock.class);
        service = new CouponClaimService(coupons, userCoupons, ledger, rowLock);
    }

    private ShopCoupon stub(ShopCoupon c) {
        when(coupons.findByPublicToken(c.getPublicToken())).thenReturn(Optional.of(c));
        when(rowLock.lockFresh(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private static void assertProblem(Throwable t, URI type, HttpStatus status) {
        assertThat(t).isInstanceOf(AppException.class);
        AppException e = (AppException) t;
        assertThat(e.getType()).isEqualTo(type);
        assertThat(e.getStatus()).isEqualTo(status);
    }

    @Test
    void unknownTokenIs404() {
        when(coupons.findByPublicToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.claim(1L, "nope", CouponClaimSource.HOME, NOW))
                .satisfies(t -> assertProblem(t, ErrorTypes.COUPON_NOT_FOUND, HttpStatus.NOT_FOUND));
        verify(rowLock, never()).lockFresh(anyLong());
    }

    @Test
    void newbieAndCodeTemplatesCannotBeClaimedDirectly() {
        for (CouponAcquireType t : new CouponAcquireType[] {CouponAcquireType.NEWBIE, CouponAcquireType.CODE}) {
            ShopCoupon c = stub(coupon(9L, t));
            assertThatThrownBy(() -> service.claim(1L, c.getPublicToken(), CouponClaimSource.HOME, NOW))
                    .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_NOT_FOUND, HttpStatus.NOT_FOUND));
        }
        verify(ledger, never()).issue(anyLong(), any(), any(), any());
    }

    @Test
    void stoppedIsNotInPeriod() {
        ShopCoupon c = stub(CouponFixtures.stopped(coupon(1L, CouponAcquireType.CLAIM)));
        assertThatThrownBy(() -> service.claim(1L, c.getPublicToken(), CouponClaimSource.HOME, NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_NOT_IN_PERIOD, HttpStatus.CONFLICT));
        verify(rowLock).lockFresh(1L);
    }

    @Test
    void outsideIssuePeriodIsNotInPeriod() {
        ShopCoupon c = stub(coupon(1L, CouponAcquireType.CLAIM));
        // 发放期左闭右开：恰在 issue_end_at → 不在期内
        assertThatThrownBy(() -> service.claim(1L, c.getPublicToken(), CouponClaimSource.HOME, c.getIssueEndAt()))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_NOT_IN_PERIOD, HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.claim(1L, c.getPublicToken(), CouponClaimSource.HOME,
                c.getIssueStartAt().minusSeconds(1)))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_NOT_IN_PERIOD, HttpStatus.CONFLICT));
    }

    @Test
    void perUserLimitCountsAnyStatusAndPrecedesSoldOut() {
        ShopCoupon c = stub(CouponFixtures.quantity(coupon(1L, CouponAcquireType.CLAIM), 10, 10));
        // 仓库按 (user, coupon) 计数，含 USED / VOIDED 行
        when(userCoupons.countByUserIdAndCouponId(1L, 1L)).thenReturn(1L);
        assertThatThrownBy(() -> service.claim(1L, c.getPublicToken(), CouponClaimSource.HOME, NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_ALREADY_CLAIMED, HttpStatus.CONFLICT));
        verify(coupons, never()).incrementIssuedIfAvailable(any());
    }

    @Test
    void soldOutWhenConditionalIncrementHitsZeroRows() {
        ShopCoupon c = stub(coupon(1L, CouponAcquireType.CLAIM));
        when(userCoupons.countByUserIdAndCouponId(1L, 1L)).thenReturn(0L);
        when(coupons.incrementIssuedIfAvailable(1L)).thenReturn(0);
        assertThatThrownBy(() -> service.claim(1L, c.getPublicToken(), CouponClaimSource.HOME, NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_SOLD_OUT, HttpStatus.CONFLICT));
        verify(ledger, never()).issue(anyLong(), any(), any(), any());
    }

    @Test
    void bannerSourceLandsAsBannerOthersAsClaim() {
        ShopCoupon c = stub(CouponFixtures.perUser(coupon(1L, CouponAcquireType.CLAIM), 5));
        when(coupons.incrementIssuedIfAvailable(1L)).thenReturn(1);
        when(ledger.issue(anyLong(), any(), any(), any())).thenAnswer(inv -> CouponFixtures.userCoupon(3L, 1L, c,
                inv.getArgument(2), UserCouponStatus.AVAILABLE, NOW.plus(Duration.ofDays(5))));

        service.claim(1L, c.getPublicToken(), CouponClaimSource.BANNER, NOW);
        verify(ledger).issue(1L, c, UserCouponSource.BANNER, NOW);

        service.claim(1L, c.getPublicToken(), CouponClaimSource.HOME, NOW);
        service.claim(1L, c.getPublicToken(), CouponClaimSource.DETAIL, NOW);
        verify(ledger, org.mockito.Mockito.times(2)).issue(eq(1L), eq(c), eq(UserCouponSource.CLAIM), eq(NOW));
    }

    @Test
    void successReturnsOwnedView() {
        ShopCoupon c = stub(coupon(1L, CouponAcquireType.CLAIM));
        when(coupons.incrementIssuedIfAvailable(1L)).thenReturn(1);
        when(ledger.issue(1L, c, UserCouponSource.CLAIM, NOW)).thenReturn(CouponFixtures.userCoupon(3L, 1L, c,
                UserCouponSource.CLAIM, UserCouponStatus.AVAILABLE, NOW.plus(Duration.ofDays(5))));
        CouponView v = service.claim(1L, c.getPublicToken(), CouponClaimSource.HOME, NOW);
        assertThat(v.claimed()).isTrue();
        assertThat(v.userCouponToken()).isEqualTo("uc3");
        assertThat(v.couponToken()).isEqualTo("tpl1");
        assertThat(v.status()).isEqualTo("AVAILABLE");
        assertThat(v.isNew()).isFalse();
    }

    @Test
    void sourceParsingIsCaseInsensitiveAndRejectsUnknown() {
        assertThat(CouponClaimSource.parse("banner")).isEqualTo(CouponClaimSource.BANNER);
        assertThat(CouponClaimSource.parse(" Detail ")).isEqualTo(CouponClaimSource.DETAIL);
        assertThatThrownBy(() -> CouponClaimSource.parse("PUSH"))
                .satisfies(e -> assertProblem(e, ErrorTypes.VALIDATION, HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> CouponClaimSource.parse(null))
                .satisfies(e -> assertProblem(e, ErrorTypes.VALIDATION, HttpStatus.UNPROCESSABLE_ENTITY));
    }
}

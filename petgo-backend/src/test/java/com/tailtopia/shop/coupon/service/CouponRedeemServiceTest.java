package com.tailtopia.shop.coupon.service;

import static com.tailtopia.shop.coupon.CouponFixtures.NOW;
import static com.tailtopia.shop.coupon.CouponFixtures.coupon;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.coupon.CouponFixtures;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import com.tailtopia.shop.coupon.dto.CouponView;
import com.tailtopia.shop.coupon.repository.ShopCouponCodeRepository;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

/** L0：v1.4.2 shop Story 2.2 AC3 —— 兑换判序、只对「不存在 / 已被使用」计失败、10 次锁定、Redis fail-open。 */
class CouponRedeemServiceTest {

    private static final long UID = 42L;
    private static final String FAIL_KEY = "rl:coupon:redeem-fail:42";
    private static final String LOCK_KEY = "rl:coupon:redeem-lock:42";

    private ShopCouponCodeRepository codes;
    private ShopCouponRepository coupons;
    private UserCouponRepository userCoupons;
    private CouponLedgerService ledger;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private CouponRowLock rowLock;
    private CouponRedeemService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        codes = mock(ShopCouponCodeRepository.class);
        coupons = mock(ShopCouponRepository.class);
        userCoupons = mock(UserCouponRepository.class);
        ledger = mock(CouponLedgerService.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.hasKey(LOCK_KEY)).thenReturn(false);
        when(ops.increment(FAIL_KEY)).thenReturn(1L);
        rowLock = mock(CouponRowLock.class);
        when(redis.getExpire(FAIL_KEY)).thenReturn(3000L);
        service = new CouponRedeemService(codes, coupons, userCoupons, ledger, redis, rowLock);
    }

    private static void assertProblem(Throwable t, URI type, HttpStatus status) {
        assertThat(t).isInstanceOf(AppException.class);
        assertThat(((AppException) t).getType()).isEqualTo(type);
        assertThat(((AppException) t).getStatus()).isEqualTo(status);
    }

    private ShopCoupon stubCode(String code, boolean singleUse, java.time.Instant redeemedAt) {
        ShopCoupon c = coupon(5L, CouponAcquireType.CODE);
        when(codes.findByCode(code)).thenReturn(Optional.of(CouponFixtures.code(11L, 5L, code, singleUse, redeemedAt)));
        when(rowLock.lockFresh(5L)).thenReturn(Optional.of(c));
        return c;
    }

    private void stubIssue(ShopCoupon c) {
        when(coupons.incrementIssuedIfAvailable(c.getId())).thenReturn(1);
        when(ledger.issue(UID, c, UserCouponSource.CODE, NOW)).thenReturn(CouponFixtures.userCoupon(8L, UID, c,
                UserCouponSource.CODE, UserCouponStatus.AVAILABLE, NOW.plus(Duration.ofDays(3))));
    }

    @Test
    void lockedRejectsEvenCorrectCodeWithoutTouchingDb() {
        when(redis.hasKey(LOCK_KEY)).thenReturn(true);
        assertThatThrownBy(() -> service.redeem(UID, "GOOD1234", NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_REDEEM_LOCKED, HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(codes, coupons, userCoupons, ledger, rowLock);
    }

    @Test
    void unknownCodeCountsFailure() {
        when(codes.findByCode("NOPE1234")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.redeem(UID, "NOPE1234", NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_NOT_FOUND, HttpStatus.NOT_FOUND));
        verify(ops).increment(FAIL_KEY);
        verify(redis).expire(FAIL_KEY, Duration.ofHours(1));
    }

    @Test
    void malformedCodeIsTreatedAsNotFoundAndCounted() {
        assertThatThrownBy(() -> service.redeem(UID, "ab", NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_NOT_FOUND, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.redeem(UID, "   ", NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_NOT_FOUND, HttpStatus.NOT_FOUND));
        verify(codes, never()).findByCode(anyString());
        verify(ops, org.mockito.Mockito.times(2)).increment(FAIL_KEY);
    }

    @Test
    void codeIsNormalizedTrimUpper() {
        ShopCoupon c = stubCode("WELCOME10", false, null);
        stubIssue(c);
        CouponView v = service.redeem(UID, "  welcome10 ", NOW);
        assertThat(v.isNew()).isTrue();
        assertThat(v.claimed()).isTrue();
        verify(codes).findByCode("WELCOME10");
        verify(ops, never()).increment(anyString());
    }

    @Test
    void usedSingleUseCodeCountsFailure() {
        stubCode("ONCE1234", true, NOW.minusSeconds(60));
        assertThatThrownBy(() -> service.redeem(UID, "ONCE1234", NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_CODE_USED, HttpStatus.CONFLICT));
        verify(ops).increment(FAIL_KEY);
        verify(coupons, never()).incrementIssuedIfAvailable(any());
    }

    @Test
    void notInPeriodDoesNotCount() {
        ShopCoupon c = stubCode("CODE1234", false, null);
        CouponFixtures.stopped(c);
        assertThatThrownBy(() -> service.redeem(UID, "CODE1234", NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_NOT_IN_PERIOD, HttpStatus.CONFLICT));
        verify(ops, never()).increment(anyString());
    }

    @Test
    void perUserLimitDoesNotCount() {
        stubCode("CODE1234", false, null);
        when(userCoupons.countByUserIdAndCouponId(UID, 5L)).thenReturn(1L);
        assertThatThrownBy(() -> service.redeem(UID, "CODE1234", NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_ALREADY_CLAIMED, HttpStatus.CONFLICT));
        verify(ops, never()).increment(anyString());
    }

    @Test
    void soldOutDoesNotCount() {
        stubCode("CODE1234", false, null);
        when(coupons.incrementIssuedIfAvailable(5L)).thenReturn(0);
        assertThatThrownBy(() -> service.redeem(UID, "CODE1234", NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_SOLD_OUT, HttpStatus.CONFLICT));
        verify(ops, never()).increment(anyString());
    }

    @Test
    void tenthFailureLocksAndDeletesCounterButReturnsOriginalError() {
        when(codes.findByCode("NOPE1234")).thenReturn(Optional.empty());
        when(ops.increment(FAIL_KEY)).thenReturn(10L);
        assertThatThrownBy(() -> service.redeem(UID, "NOPE1234", NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_NOT_FOUND, HttpStatus.NOT_FOUND));
        verify(ops).set(LOCK_KEY, "1", Duration.ofHours(1));
        verify(redis).delete(FAIL_KEY);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void missingTtlOnFailCounterIsRepaired() {
        // INCR 与 EXPIRE 非原子：首次 EXPIRE 丢了时，后续失败发现 TTL=-1 就补设
        when(codes.findByCode("NOPE1234")).thenReturn(Optional.empty());
        when(ops.increment(FAIL_KEY)).thenReturn(3L);
        when(redis.getExpire(FAIL_KEY)).thenReturn(-1L);
        assertThatThrownBy(() -> service.redeem(UID, "NOPE1234", NOW)).isInstanceOf(AppException.class);
        verify(redis).expire(FAIL_KEY, Duration.ofHours(1));
    }

    @Test
    void existingTtlOnFailCounterIsNotExtended() {
        when(codes.findByCode("NOPE1234")).thenReturn(Optional.empty());
        when(ops.increment(FAIL_KEY)).thenReturn(3L);
        when(redis.getExpire(FAIL_KEY)).thenReturn(1200L);
        assertThatThrownBy(() -> service.redeem(UID, "NOPE1234", NOW)).isInstanceOf(AppException.class);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void ninthFailureDoesNotLock() {
        when(codes.findByCode("NOPE1234")).thenReturn(Optional.empty());
        when(ops.increment(FAIL_KEY)).thenReturn(9L);
        assertThatThrownBy(() -> service.redeem(UID, "NOPE1234", NOW)).isInstanceOf(AppException.class);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void singleUseRaceLostIsCodeUsedAndCounted() {
        ShopCoupon c = stubCode("ONCE1234", true, null);
        when(coupons.incrementIssuedIfAvailable(5L)).thenReturn(1);
        when(codes.redeemSingleUse(11L, UID, NOW)).thenReturn(0);
        assertThatThrownBy(() -> service.redeem(UID, "ONCE1234", NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_CODE_USED, HttpStatus.CONFLICT));
        verify(ops).increment(FAIL_KEY);
        verify(ledger, never()).issue(anyLong(), any(), any(), any());
        assertThat(c.getId()).isEqualTo(5L);
    }

    @Test
    void singleUseSuccessMarksCodeThenIssues() {
        ShopCoupon c = stubCode("ONCE1234", true, null);
        stubIssue(c);
        when(codes.redeemSingleUse(11L, UID, NOW)).thenReturn(1);
        CouponView v = service.redeem(UID, "ONCE1234", NOW);
        assertThat(v.userCouponToken()).isEqualTo("uc8");
        verify(ledger).issue(UID, c, UserCouponSource.CODE, NOW);
    }

    @Test
    void redisFailureFailsOpen() {
        when(redis.hasKey(LOCK_KEY)).thenThrow(new RedisConnectionFailureException("down"));
        when(ops.increment(FAIL_KEY)).thenThrow(new RedisConnectionFailureException("down"));
        ShopCoupon c = stubCode("CODE1234", false, null);
        stubIssue(c);
        // 锁定检查异常 → 放行兑换
        assertThat(service.redeem(UID, "CODE1234", NOW).couponToken()).isEqualTo("tpl5");
        // 失败计数写入异常 → 仍返回业务错误，不变 500
        when(codes.findByCode("NOPE1234")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.redeem(UID, "NOPE1234", NOW))
                .satisfies(e -> assertProblem(e, ErrorTypes.COUPON_NOT_FOUND, HttpStatus.NOT_FOUND));
    }
}

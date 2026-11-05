package com.tailtopia.shop.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponReleaseMode;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.repository.UserCouponLedgerDao;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 2.1 AC4 / AC5 —— 账本（mock DAO）。 */
class CouponLedgerServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private UserCouponLedgerDao dao;
    private UserCouponRepository repo;
    private ShopTokenGenerator tokens;
    private CouponLedgerService ledger;

    @BeforeEach
    void setUp() {
        dao = mock(UserCouponLedgerDao.class);
        repo = mock(UserCouponRepository.class);
        tokens = mock(ShopTokenGenerator.class);
        ledger = new CouponLedgerService(dao, repo, tokens);
    }

    private static ShopCoupon relative(int days) {
        ShopCoupon c = ShopCoupon.create("ct", CouponKind.DISCOUNT, "Diskon", "Discount", 10_000, 50_000,
                CouponScopeType.ALL, List.of(), CouponAcquireType.CLAIM,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"),
                CouponValidMode.RELATIVE, null, null, days, null, 1);
        ReflectionTestUtils.setField(c, "id", 7L);
        return c;
    }

    @Test
    void lockThrowsWithFailedTokenWhenAnyUpdateHitsZeroRows() {
        when(dao.lock("ok", 1L, 99L, NOW)).thenReturn(1);
        when(dao.lock("bad", 1L, 99L, NOW)).thenReturn(0);

        assertThatThrownBy(() -> ledger.lock(1L, 99L, List.of("ok", "bad"), NOW))
                .isInstanceOfSatisfying(CouponInvalidException.class, e -> {
                    assertThat(e.getUserCouponTokens()).containsExactly("bad");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getType()).isEqualTo(ErrorTypes.COUPON_INVALID);
                    assertThat(e.problemExtensions()).containsEntry("userCouponTokens", List.of("bad"));
                });
    }

    @Test
    void lockAllOkDoesNotThrowAndDedupes() {
        when(dao.lock(anyString(), eq(1L), eq(99L), eq(NOW))).thenReturn(1);
        ledger.lock(1L, 99L, List.of("a", "a", "b"), NOW);
        verify(dao, times(1)).lock("a", 1L, 99L, NOW);
        verify(dao, times(1)).lock("b", 1L, 99L, NOW);
    }

    @Test
    void relativeValidityFollowsWibDayBoundary() {
        ShopCoupon c = relative(3);
        // UTC 16:59 = WIB 23:59（11-05）→ 到期 11-08 23:59:59 WIB
        Instant before = Instant.parse("2026-11-05T16:59:00Z");
        // UTC 17:01 = WIB 00:01（11-06）→ 到期 11-09 23:59:59 WIB
        Instant after = Instant.parse("2026-11-05T17:01:00Z");
        Instant[] v1 = CouponLedgerService.materializeValidity(c, before);
        Instant[] v2 = CouponLedgerService.materializeValidity(c, after);
        assertThat(v1[0]).isEqualTo(before);
        assertThat(v1[1]).isEqualTo(Instant.parse("2026-11-08T16:59:59Z"));
        assertThat(v2[0]).isEqualTo(after);
        assertThat(v2[1]).isEqualTo(Instant.parse("2026-11-09T16:59:59Z"));
    }

    @Test
    void fixedValidityCopiesTemplate() {
        Instant vs = Instant.parse("2026-10-01T00:00:00Z");
        Instant ve = Instant.parse("2026-12-31T16:59:59Z");
        ShopCoupon c = ShopCoupon.create("ct", CouponKind.FREE_SHIPPING, "Gratis", "Free", 15_000, 0,
                CouponScopeType.ALL, List.of(), CouponAcquireType.CLAIM, vs, ve,
                CouponValidMode.FIXED, vs, ve, null, null, 1);
        Instant[] v = CouponLedgerService.materializeValidity(c, NOW);
        assertThat(v).containsExactly(vs, ve);
    }

    @Test
    void issueInsertsThenReadsBack() {
        ShopCoupon c = relative(1);
        UserCoupon uc = mock(UserCoupon.class);
        when(tokens.generate()).thenReturn("uct");
        when(dao.insert(eq("uct"), eq(5L), eq(7L), eq("CLAIM"), any(), any(), eq(NOW))).thenReturn(42L);
        when(repo.findById(42L)).thenReturn(Optional.of(uc));

        assertThat(ledger.issue(5L, c, UserCouponSource.CLAIM, NOW)).isSameAs(uc);
        ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
        verify(dao).insert(eq("uct"), eq(5L), eq(7L), eq("CLAIM"), eq(NOW), until.capture(), eq(NOW));
        assertThat(until.getValue()).isEqualTo(Instant.parse("2026-11-06T16:59:59Z"));
    }

    @Test
    void releaseKeepDoesNotTouchDao() {
        assertThat(ledger.releaseOnCancel(99L, CouponReleaseMode.KEEP, NOW)).isZero();
        verifyNoInteractions(dao);
    }

    @Test
    void releaseReturnCallsReturnStatement() {
        when(dao.releaseReturn(99L, NOW)).thenReturn(2);
        assertThat(ledger.releaseOnCancel(99L, CouponReleaseMode.RETURN, NOW)).isEqualTo(2);
        verify(dao).releaseReturn(99L, NOW);
    }

    @Test
    void markUsedIsIdempotentPassThrough() {
        when(dao.markUsed(99L, NOW)).thenReturn(1, 0);
        assertThat(ledger.markUsed(99L, NOW)).isEqualTo(1);
        assertThat(ledger.markUsed(99L, NOW)).isZero();
    }

    @Test
    void voidMethodsDelegate() {
        when(dao.voidUnusedByCoupon(7L, NOW)).thenReturn(3);
        when(dao.voidAvailableByUser(5L, NOW)).thenReturn(4);
        assertThat(ledger.voidUnused(7L, NOW)).isEqualTo(3);
        assertThat(ledger.voidAllAvailableForUser(5L, NOW)).isEqualTo(4);
        verify(dao, never()).releaseReturn(anyLong(), any());
    }
}

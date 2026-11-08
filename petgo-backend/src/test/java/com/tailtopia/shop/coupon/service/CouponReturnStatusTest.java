package com.tailtopia.shop.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.coupon.domain.CouponReleaseMode;
import com.tailtopia.shop.coupon.domain.CouponReturnStatus;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import com.tailtopia.shop.coupon.repository.UserCouponLedgerDao;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（v1.4.2 shop Story 6.2 AC3.3，P-2）：{@code CouponLedgerService.couponReturnStatusOf} 只读判定。 */
class CouponReturnStatusTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final long ORDER = 9L;

    private UserCouponLedgerDao dao;
    private UserCouponRepository repo;
    private CouponLedgerService ledger;

    @BeforeEach
    void setUp() {
        dao = mock(UserCouponLedgerDao.class);
        repo = mock(UserCouponRepository.class);
        ledger = new CouponLedgerService(dao, repo, mock(ShopTokenGenerator.class));
    }

    private static UserCoupon coupon(long id, UserCouponStatus status, Instant validUntil, Long lockedOrder) {
        UserCoupon uc = org.springframework.beans.BeanUtils.instantiateClass(UserCoupon.class);
        ReflectionTestUtils.setField(uc, "id", id);
        ReflectionTestUtils.setField(uc, "status", status);
        ReflectionTestUtils.setField(uc, "validFrom", NOW.minusSeconds(86_400));
        ReflectionTestUtils.setField(uc, "validUntil", validUntil);
        ReflectionTestUtils.setField(uc, "lockedOrderId", lockedOrder);
        return uc;
    }

    private CouponReturnStatus of(CouponReleaseMode mode, UserCoupon... rows) {
        when(repo.findByIdIn(anyCollection())).thenReturn(List.of(rows));
        return ledger.couponReturnStatusOf(ORDER, Arrays.asList(5L, null), mode, NOW);
    }

    @Test
    @DisplayName("未用券 → null；不退券原因（KEEP）→ NOT_RETURNED，均不查库；全程不写（DAO 零交互）")
    void noCouponOrKeep() {
        assertThat(ledger.couponReturnStatusOf(ORDER, Arrays.asList(null, null), CouponReleaseMode.RETURN, NOW))
                .isNull();
        assertThat(ledger.couponReturnStatusOf(ORDER, List.of(5L), CouponReleaseMode.KEEP, NOW))
                .isEqualTo(CouponReturnStatus.NOT_RETURNED);
        verifyNoInteractions(repo, dao);
    }

    @Test
    @DisplayName("AVAILABLE 未过期 → RETURNED；已过期 → EXPIRED；VOIDED → NOT_RETURNED；仍锁在本单 → NOT_RETURNED；锁到别单 → RETURNED")
    void statuses() {
        assertThat(of(CouponReleaseMode.RETURN, coupon(5, UserCouponStatus.AVAILABLE, NOW.plusSeconds(60), null)))
                .isEqualTo(CouponReturnStatus.RETURNED);
        assertThat(of(CouponReleaseMode.RETURN, coupon(5, UserCouponStatus.AVAILABLE, NOW.minusSeconds(60), null)))
                .isEqualTo(CouponReturnStatus.EXPIRED);
        assertThat(of(CouponReleaseMode.RETURN, coupon(5, UserCouponStatus.VOIDED, NOW.plusSeconds(60), null)))
                .isEqualTo(CouponReturnStatus.NOT_RETURNED);
        assertThat(of(CouponReleaseMode.RETURN, coupon(5, UserCouponStatus.LOCKED, NOW.plusSeconds(60), ORDER)))
                .isEqualTo(CouponReturnStatus.NOT_RETURNED);
        assertThat(of(CouponReleaseMode.RETURN, coupon(5, UserCouponStatus.LOCKED, NOW.plusSeconds(60), 77L)))
                .isEqualTo(CouponReturnStatus.RETURNED);
        verifyNoInteractions(dao);
    }

    @Test
    @DisplayName("两张券：任一过期 → EXPIRED；否则任一退回 → RETURNED")
    void twoCoupons() {
        assertThat(of(CouponReleaseMode.RETURN,
                coupon(5, UserCouponStatus.AVAILABLE, NOW.plusSeconds(60), null),
                coupon(6, UserCouponStatus.AVAILABLE, NOW.minusSeconds(60), null)))
                .isEqualTo(CouponReturnStatus.EXPIRED);
        assertThat(of(CouponReleaseMode.RETURN,
                coupon(5, UserCouponStatus.AVAILABLE, NOW.plusSeconds(60), null),
                coupon(6, UserCouponStatus.VOIDED, NOW.plusSeconds(60), null)))
                .isEqualTo(CouponReturnStatus.RETURNED);
    }
}

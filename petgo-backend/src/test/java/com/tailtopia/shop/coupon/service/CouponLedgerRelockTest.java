package com.tailtopia.shop.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import com.tailtopia.shop.coupon.repository.UserCouponLedgerDao;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * L0：v1.4.2 shop Story 4.2 AC2.4 —— {@code CouponLedgerService.relockForReorder}（mock DAO）。
 *
 * <p>条件语句本身的语义（只认 AVAILABLE、不看有效期）由 SQL 文本断言钉住；库级行为（已过期仍可锁、LOCKED / USED / VOIDED
 * 影响 0 行）由 L1 {@code LatePaymentIntegrationTest} 验证。
 */
class CouponLedgerRelockTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private UserCouponLedgerDao dao;
    private UserCouponRepository repo;
    private CouponLedgerService ledger;

    @BeforeEach
    void setUp() {
        dao = mock(UserCouponLedgerDao.class);
        repo = mock(UserCouponRepository.class);
        ledger = new CouponLedgerService(dao, repo, mock(ShopTokenGenerator.class));
    }

    private static UserCoupon coupon(long id, String token, UserCouponStatus status, Instant validUntil) {
        UserCoupon uc = org.springframework.beans.BeanUtils.instantiateClass(UserCoupon.class);
        ReflectionTestUtils.setField(uc, "id", id);
        ReflectionTestUtils.setField(uc, "publicToken", token);
        ReflectionTestUtils.setField(uc, "status", status);
        ReflectionTestUtils.setField(uc, "validUntil", validUntil);
        return uc;
    }

    @Test
    @DisplayName("SQL：只认 AVAILABLE + 本人，🔴 不含有效期条件（已过期仍可用于新单）；LOCKED 到新单、不新建行")
    void relockSqlIgnoresValidityAndOnlyAcceptsAvailable() {
        String sql = UserCouponLedgerDao.RELOCK_FOR_REORDER_SQL;
        // V1.4.2 shop-admin Story 2.1：同一条语句用 CTE 补写 LOCKED 流水（user_coupon_events），用户券本身仍不新建行
        assertThat(sql).startsWith("WITH u AS (").contains("UPDATE user_coupons")
                .contains("status = 'LOCKED'", "locked_order_id = :orderId", "status = 'AVAILABLE'",
                        "user_id = :userId", "id = :id")
                .contains("INSERT INTO user_coupon_events").contains("'LOCKED', :orderId")
                .doesNotContain("valid_from").doesNotContain("valid_until").doesNotContain("INSERT INTO user_coupons");
    }

    @Test
    @DisplayName("AVAILABLE（含已过期）→ 影响 1 行，不抛")
    void availableCouponRelocks() {
        when(dao.relockForReorder(5L, 1L, 99L, NOW)).thenReturn(1);
        ledger.relockForReorder(1L, 5L, 99L, NOW);
        verify(dao).relockForReorder(5L, 1L, 99L, NOW);
    }

    @Test
    @DisplayName("LOCKED / USED / VOIDED（影响 0 行）→ CouponInvalidException，带该券 token、不带内部 id")
    void nonAvailableCouponRejected() {
        for (UserCouponStatus st : List.of(UserCouponStatus.LOCKED, UserCouponStatus.USED, UserCouponStatus.VOIDED)) {
            when(dao.relockForReorder(5L, 1L, 99L, NOW)).thenReturn(0);
            when(repo.findById(5L)).thenReturn(Optional.of(coupon(5L, "uc-tok-5", st, NOW.plusSeconds(60))));
            assertThatThrownBy(() -> ledger.relockForReorder(1L, 5L, 99L, NOW))
                    .as(st.name())
                    .isInstanceOfSatisfying(CouponInvalidException.class, e -> {
                        assertThat(e.getType()).isEqualTo(ErrorTypes.COUPON_INVALID);
                        assertThat(e.getUserCouponTokens()).containsExactly("uc-tok-5");
                    });
        }
    }

    @Test
    @DisplayName("读不回券 → 仍抛 CouponInvalidException，token 列表为空（不拿内部 id 充数）")
    void missingCouponYieldsEmptyTokens() {
        when(dao.relockForReorder(5L, 1L, 99L, NOW)).thenReturn(0);
        when(repo.findById(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> ledger.relockForReorder(1L, 5L, 99L, NOW))
                .isInstanceOfSatisfying(CouponInvalidException.class,
                        e -> assertThat(e.getUserCouponTokens()).isEmpty());
    }

    @Test
    @DisplayName("MANDATORY：必须在调用方（重下单）事务内")
    void mandatoryPropagation() throws NoSuchMethodException {
        Method m = CouponLedgerService.class.getMethod("relockForReorder", long.class, long.class, long.class,
                Instant.class);
        assertThat(m.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }
}

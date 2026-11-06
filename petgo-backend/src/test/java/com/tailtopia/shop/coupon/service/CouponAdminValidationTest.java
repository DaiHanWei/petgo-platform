package com.tailtopia.shop.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponStatus;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import com.tailtopia.shop.coupon.repository.ShopCouponCodeRepository;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import com.tailtopia.shop.coupon.service.CouponAdminService.AdminCouponStatus;
import com.tailtopia.shop.coupon.service.CouponAdminService.CouponRisk;
import com.tailtopia.shop.coupon.service.CouponAdminService.CouponRiskNotAcknowledgedException;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（V1.4.2 shop-admin Story 2.1 · AD-6）：编辑 / 加总量 / 防误配 / 状态现算 / 详情数据 / 批量码补差额。 */
class CouponAdminValidationTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant FUTURE_S = Instant.parse("2026-11-20T00:00:00Z");
    private static final Instant FUTURE_E = Instant.parse("2026-11-30T00:00:00Z");
    private static final Instant PAST_S = Instant.parse("2026-11-01T00:00:00Z");
    private static final Long ACTOR = 9L;

    private ShopCouponRepository coupons;
    private ShopCouponCodeRepository codes;
    private UserCouponRepository userCoupons;
    private CouponNewbieLock newbieLock;
    private AdminAuditService audit;
    private CouponAdminService service;
    private CouponRowLock rowLock;

    @BeforeEach
    void setUp() {
        coupons = mock(ShopCouponRepository.class);
        codes = mock(ShopCouponCodeRepository.class);
        userCoupons = mock(UserCouponRepository.class);
        newbieLock = mock(CouponNewbieLock.class);
        audit = mock(AdminAuditService.class);
        ShopTokenGenerator tokens = mock(ShopTokenGenerator.class);
        when(tokens.generate()).thenReturn("tok");
        when(coupons.save(any(ShopCoupon.class))).thenAnswer(i -> i.getArgument(0));
        when(coupons.saveAndFlush(any(ShopCoupon.class))).thenAnswer(i -> i.getArgument(0));
        when(codes.findExistingCodes(anyCollection())).thenReturn(Set.of());
        service = new CouponAdminService(coupons, codes, mock(ShopProductRepository.class),
                mock(CouponLedgerService.class), userCoupons, newbieLock, tokens, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        rowLock = mock(CouponRowLock.class);
        service.setRowLock(rowLock);
    }

    private static CouponDraft draft(CouponAcquireType acquire, Instant is, Instant ie, long minSpend, Integer total) {
        return new CouponDraft(CouponKind.DISCOUNT, "Diskon", "Discount", 10_000L, minSpend, CouponScopeType.ALL, null,
                acquire, is, ie, CouponValidMode.RELATIVE, null, null, 7, total, 1);
    }

    private ShopCoupon stored(CouponAcquireType acquire, Instant is, Instant ie, Integer total) {
        ShopCoupon c = ShopCoupon.create("ct", CouponKind.DISCOUNT, "Diskon", "Discount", 10_000L, 50_000L,
                CouponScopeType.ALL, List.of(), acquire, is, ie, CouponValidMode.RELATIVE, null, null, 7, total, 1);
        ReflectionTestUtils.setField(c, "id", 77L);
        when(coupons.findByPublicToken("ct")).thenReturn(Optional.of(c));
        when(rowLock.lockFresh(77L)).thenReturn(Optional.of(c));
        return c;
    }

    // ---------- AC3：O-14 硬拒 + 防误配 ----------

    @Test
    void discountNotBelowMinSpendIsRejectedOutright() {
        CouponDraft d = new CouponDraft(CouponKind.DISCOUNT, "D", "D", 50_000L, 50_000L, CouponScopeType.ALL, null,
                CouponAcquireType.CLAIM, FUTURE_S, FUTURE_E, CouponValidMode.RELATIVE, null, null, 7, 10, 1);
        assertThatThrownBy(() -> service.create(d, true, ACTOR)).isInstanceOf(AppException.class)
                .hasMessageContaining("小于使用门槛");
        verify(coupons, never()).save(any());
    }

    @Test
    void riskyDraftIsNotSavedWithoutAcknowledgement() {
        CouponDraft d = draft(CouponAcquireType.CLAIM, FUTURE_S, FUTURE_E, 0L, null);
        assertThatThrownBy(() -> service.create(d, false, ACTOR))
                .isInstanceOfSatisfying(CouponRiskNotAcknowledgedException.class, e -> assertThat(e.risks())
                        .containsExactly(CouponRisk.MIN_SPEND_ZERO, CouponRisk.UNLIMITED_QUANTITY));
        verify(coupons, never()).save(any());
        assertThat(service.create(d, true, ACTOR)).isNotNull();
        verify(coupons).save(any());
    }

    @Test
    void newbieUnlimitedTotalIsNotARisk() {
        assertThat(service.risks(draft(CouponAcquireType.NEWBIE, FUTURE_S, FUTURE_E, 50_000L, null))).isEmpty();
    }

    // ---------- AC1：编辑 ----------

    @Test
    void notStartedCouponCanChangeEverythingAndAuditsOnlyChangedFields() {
        ShopCoupon c = stored(CouponAcquireType.CLAIM, FUTURE_S, FUTURE_E, 100);
        CouponDraft d = new CouponDraft(CouponKind.DISCOUNT, "Diskon Baru", "Discount", 15_000L, 50_000L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, FUTURE_S, FUTURE_E, CouponValidMode.RELATIVE,
                null, null, 7, 100, 1);
        service.update("ct", d, false, ACTOR);
        assertThat(c.getNameId()).isEqualTo("Diskon Baru");
        assertThat(c.getAmount()).isEqualTo(15_000L);
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_COUPON_UPDATED), anyString(), eq("ct"),
                eq("nameId: Diskon→Diskon Baru amount: 10000→15000"));
    }

    @Test
    void startedCouponOnlyAcceptsHigherTotal() {
        ShopCoupon c = stored(CouponAcquireType.CLAIM, PAST_S, FUTURE_E, 100);
        CouponDraft other = new CouponDraft(CouponKind.DISCOUNT, "X", "Discount", 10_000L, 50_000L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, PAST_S, FUTURE_E, CouponValidMode.RELATIVE,
                null, null, 7, 100, 1);
        assertThatThrownBy(() -> service.update("ct", other, true, ACTOR)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.raiseTotalQuantity("ct", 50, true, ACTOR)).isInstanceOf(AppException.class)
                .hasMessageContaining("只能增加");
        CouponDraft more = new CouponDraft(CouponKind.DISCOUNT, "Diskon", "Discount", 10_000L, 50_000L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, PAST_S, FUTURE_E, CouponValidMode.RELATIVE,
                null, null, 7, 150, 1);
        service.update("ct", more, false, ACTOR);
        assertThat(c.getTotalQuantity()).isEqualTo(150);
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_COUPON_QUOTA_INCREASED), anyString(), eq("ct"),
                eq("total: 100→150"));
    }

    @Test
    void stoppedCouponCannotBeEdited() {
        ShopCoupon c = stored(CouponAcquireType.CLAIM, FUTURE_S, FUTURE_E, 100);
        ReflectionTestUtils.setField(c, "status", CouponStatus.STOPPED);
        assertThatThrownBy(() -> service.raiseTotalQuantity("ct", 200, true, ACTOR)).isInstanceOf(AppException.class);
    }

    @Test
    void editingANewbieCouponExcludesItselfFromTheOverlapCheck() {
        stored(CouponAcquireType.NEWBIE, FUTURE_S, FUTURE_E, null);
        when(coupons.findActiveNewbieOverlapping(FUTURE_S, FUTURE_E, 77L)).thenReturn(List.of());
        service.update("ct", draft(CouponAcquireType.NEWBIE, FUTURE_S, FUTURE_E, 50_000L, null), true, ACTOR);
        verify(newbieLock).acquire();
        verify(coupons).findActiveNewbieOverlapping(FUTURE_S, FUTURE_E, 77L);
    }

    @Test
    void kindOrAcquireCannotChangeOnceIssued() {
        ShopCoupon c = stored(CouponAcquireType.CLAIM, FUTURE_S, FUTURE_E, 100);
        ReflectionTestUtils.setField(c, "issuedCount", 3);
        assertThatThrownBy(() -> service.update("ct", draft(CouponAcquireType.CODE, FUTURE_S, FUTURE_E, 50_000L, 100),
                true, ACTOR)).isInstanceOf(AppException.class);
    }

    @Test
    void raisingAStartedCouponToUnlimitedNeedsAcknowledgement() {
        stored(CouponAcquireType.CLAIM, PAST_S, FUTURE_E, 100);
        assertThatThrownBy(() -> service.raiseTotalQuantity("ct", null, false, ACTOR))
                .isInstanceOf(CouponRiskNotAcknowledgedException.class);
        service.raiseTotalQuantity("ct", null, true, ACTOR);
    }

    // ---------- AC7：批量码 ----------

    @Test
    void batchCodesAreGeneratedUpToTotalInChunksOfAThousand() {
        stored(CouponAcquireType.CODE, PAST_S, FUTURE_E, 2_500);
        when(codes.countByCouponIdAndSingleUse(77L, true)).thenReturn(0L);
        when(codes.countByCouponIdAndSingleUse(77L, false)).thenReturn(0L);
        assertThat(service.generateSingleUseCodesForTotal("ct", ACTOR)).isEqualTo(2_500);
        verify(codes, times(3)).saveAllAndFlush(anyList());
        verify(audit, times(1)).record(eq(ACTOR), eq(AuditActions.SHOP_COUPON_CODES_CREATED), anyString(), eq("ct"),
                eq("singleUse=true count=2500"));
    }

    @Test
    void batchCouponWithoutTotalIsRejected() {
        stored(CouponAcquireType.CODE, PAST_S, FUTURE_E, null);
        assertThatThrownBy(() -> service.generateSingleUseCodesForTotal("ct", ACTOR)).isInstanceOf(AppException.class);
    }

    @Test
    void raisingABatchCouponTopsUpTheMissingCodes() {
        stored(CouponAcquireType.CODE, PAST_S, FUTURE_E, 100);
        when(codes.countByCouponIdAndSingleUse(77L, true)).thenReturn(100L);
        service.raiseTotalQuantity("ct", 130, false, ACTOR);
        verify(codes, times(1)).saveAllAndFlush(org.mockito.ArgumentMatchers.argThat(l -> ((List<?>) l).size() == 30));
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_COUPON_QUOTA_INCREASED), anyString(), eq("ct"),
                contains("codesAdded=30"));
        assertThatThrownBy(() -> service.raiseTotalQuantity("ct", null, true, ACTOR)).isInstanceOf(AppException.class);
    }

    // ---------- AC4 / AC5 ----------

    @Test
    void listStatusIsComputedFromTheIssuePeriod() {
        ShopCoupon notStarted = ShopCoupon.create("a", CouponKind.DISCOUNT, "a", "a", 1, 0, CouponScopeType.ALL, List.of(),
                CouponAcquireType.CLAIM, FUTURE_S, FUTURE_E, CouponValidMode.RELATIVE, null, null, 1, null, 1);
        ShopCoupon running = ShopCoupon.create("b", CouponKind.DISCOUNT, "b", "b", 1, 0, CouponScopeType.ALL, List.of(),
                CouponAcquireType.CLAIM, PAST_S, FUTURE_E, CouponValidMode.RELATIVE, null, null, 1, null, 1);
        ShopCoupon ended = ShopCoupon.create("c", CouponKind.DISCOUNT, "c", "c", 1, 0, CouponScopeType.ALL, List.of(),
                CouponAcquireType.CLAIM, PAST_S, NOW, CouponValidMode.RELATIVE, null, null, 1, null, 1);
        ShopCoupon stopped = ShopCoupon.create("d", CouponKind.DISCOUNT, "d", "d", 1, 0, CouponScopeType.ALL, List.of(),
                CouponAcquireType.CLAIM, PAST_S, FUTURE_E, CouponValidMode.RELATIVE, null, null, 1, null, 1);
        ReflectionTestUtils.setField(stopped, "status", CouponStatus.STOPPED);
        assertThat(CouponAdminService.statusOf(notStarted, NOW)).isEqualTo(AdminCouponStatus.NOT_STARTED);
        assertThat(CouponAdminService.statusOf(running, NOW)).isEqualTo(AdminCouponStatus.RUNNING);
        assertThat(CouponAdminService.statusOf(ended, NOW)).isEqualTo(AdminCouponStatus.ENDED);
        assertThat(CouponAdminService.statusOf(stopped, NOW)).isEqualTo(AdminCouponStatus.STOPPED);

        when(coupons.findForAdmin(null, null)).thenReturn(List.of(notStarted, running, ended, stopped));
        assertThat(service.listForAdmin(AdminCouponStatus.RUNNING, null, null))
                .extracting(r -> r.coupon().getPublicToken()).containsExactly("b");
    }

    @Test
    void detailStatsCountsClaimedLockedUsedAndPaidOrderAmount() {
        stored(CouponAcquireType.CLAIM, PAST_S, FUTURE_E, 100);
        when(userCoupons.countByCouponId(77L)).thenReturn(40L);
        when(userCoupons.countByCouponIdAndStatus(77L, UserCouponStatus.LOCKED)).thenReturn(3L);
        when(userCoupons.countByCouponIdAndStatus(77L, UserCouponStatus.USED)).thenReturn(12L);
        when(userCoupons.sumPaidOrderAmountUsingCoupon(77L, null, null)).thenReturn(1_860_000L);
        assertThat(service.detailStats("ct")).isEqualTo(new CouponAdminService.CouponStats(40, 3, 12, 1_860_000L));
    }

    @Test
    void exportAuditsOnlyTheCount() {
        stored(CouponAcquireType.CODE, PAST_S, FUTURE_E, 2);
        when(codes.findByCouponIdAndSingleUseOrderByIdAsc(77L, true)).thenReturn(List.of(
                com.tailtopia.shop.coupon.domain.ShopCouponCode.of(77L, "ABCDEF2345", true),
                com.tailtopia.shop.coupon.domain.ShopCouponCode.of(77L, "ABCDEF2346", true)));
        assertThat(service.exportSingleUseCodes("ct", ACTOR)).hasSize(2);
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_COUPON_CODES_EXPORTED), anyString(), eq("ct"), eq("count=2"));
    }
}

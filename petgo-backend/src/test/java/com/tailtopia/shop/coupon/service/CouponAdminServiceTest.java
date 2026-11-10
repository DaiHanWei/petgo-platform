package com.tailtopia.shop.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.tailtopia.shop.coupon.domain.ShopCouponCode;
import com.tailtopia.shop.coupon.repository.ShopCouponCodeRepository;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 2.1 AC3 —— 建券 / 停券 / 兑换码（Mockito）。 */
class CouponAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Instant IS = Instant.parse("2026-11-06T00:00:00Z");
    private static final Instant IE = Instant.parse("2026-11-20T00:00:00Z");
    private static final Long ACTOR = 3L;

    private ShopCouponRepository coupons;
    private ShopCouponCodeRepository codes;
    private ShopProductRepository products;
    private CouponLedgerService ledger;
    private com.tailtopia.shop.coupon.repository.UserCouponRepository userCoupons;
    private CouponNewbieLock newbieLock;
    private AdminAuditService audit;
    private CouponAdminService service;

    @BeforeEach
    void setUp() {
        coupons = mock(ShopCouponRepository.class);
        codes = mock(ShopCouponCodeRepository.class);
        products = mock(ShopProductRepository.class);
        ledger = mock(CouponLedgerService.class);
        userCoupons = mock(com.tailtopia.shop.coupon.repository.UserCouponRepository.class);
        newbieLock = mock(CouponNewbieLock.class);
        audit = mock(AdminAuditService.class);
        ShopTokenGenerator tokens = mock(ShopTokenGenerator.class);
        when(tokens.generate()).thenReturn("tok");
        when(coupons.save(any(ShopCoupon.class))).thenAnswer(i -> i.getArgument(0));
        service = new CouponAdminService(coupons, codes, products, ledger, userCoupons, newbieLock, tokens, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 基线：满减、门槛 50k 减 10k、全场、领取、RELATIVE 7 天。 */
    private static CouponDraft draft(CouponKind kind, long amount, long minSpend) {
        return new CouponDraft(kind, "Diskon", "Discount", amount, minSpend, CouponScopeType.ALL, null,
                CouponAcquireType.CLAIM, IS, IE, CouponValidMode.RELATIVE, null, null, 7, null, 2);
    }

    private static CouponDraft with(CouponDraft d, CouponScopeType st, List<String> sv) {
        return new CouponDraft(d.kind(), d.nameId(), d.nameEn(), d.amount(), d.minSpend(), st, sv,
                d.acquireType(), d.issueStartAt(), d.issueEndAt(), d.validMode(), d.validStartAt(),
                d.validEndAt(), d.validDays(), d.totalQuantity(), d.perUserLimit());
    }

    private static CouponDraft fixed(Instant vs, Instant ve) {
        return new CouponDraft(CouponKind.DISCOUNT, "Diskon", "Discount", 10_000L, 50_000L, CouponScopeType.ALL,
                null, CouponAcquireType.CLAIM, IS, IE, CouponValidMode.FIXED, vs, ve, null, null, 1);
    }

    private static CouponDraft acquire(CouponAcquireType t, Integer perUser) {
        return new CouponDraft(CouponKind.DISCOUNT, "Diskon", "Discount", 10_000L, 0L, CouponScopeType.ALL,
                null, t, IS, IE, CouponValidMode.RELATIVE, null, null, 7, null, perUser);
    }

    private static void assertValidation(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    private ShopCoupon existing(CouponAcquireType type) {
        ShopCoupon c = ShopCoupon.create("ct", CouponKind.DISCOUNT, "Diskon", "Discount", 10_000, 0,
                CouponScopeType.ALL, List.of(), type, IS, IE, CouponValidMode.RELATIVE, null, null, 7, null, 1);
        ReflectionTestUtils.setField(c, "id", 7L);
        when(coupons.findByPublicToken("ct")).thenReturn(Optional.of(c));
        return c;
    }

    // ---------------------------------------------------------------- 建券校验

    @Test
    void discountAmountMustBeBelowMinSpend() {
        assertValidation(() -> service.create(draft(CouponKind.DISCOUNT, 50_000, 50_000), ACTOR));
        assertValidation(() -> service.create(draft(CouponKind.DISCOUNT, 60_000, 50_000), ACTOR));
        assertThat(service.create(draft(CouponKind.DISCOUNT, 49_999, 50_000), ACTOR).getAmount()).isEqualTo(49_999);
    }

    @Test
    void discountWithoutThresholdAllowed() {
        ShopCoupon c = service.create(draft(CouponKind.DISCOUNT, 10_000, 0), ACTOR);
        assertThat(c.getMinSpend()).isZero();
        assertThat(c.getStatus()).isEqualTo(CouponStatus.ACTIVE);
        assertThat(c.getPublicToken()).isEqualTo("tok");
    }

    @Test
    void freeShippingAnyPositiveAmount() {
        assertThat(service.create(draft(CouponKind.FREE_SHIPPING, 80_000, 50_000), ACTOR).getAmount())
                .isEqualTo(80_000);
        assertValidation(() -> service.create(draft(CouponKind.FREE_SHIPPING, 0, 0), ACTOR));
        assertValidation(() -> service.create(draft(CouponKind.DISCOUNT, 10_000, -1), ACTOR));
    }

    @Test
    void fixedValidStartAfterIssueStartRejected() {
        assertValidation(() -> service.create(fixed(IS.plusSeconds(1), IE.plusSeconds(86400)), ACTOR));
        // 发放结束晚于有效期结束 → 拒
        assertValidation(() -> service.create(fixed(IS, IE.minusSeconds(1)), ACTOR));
        // 有效期结束不晚于开始 → 拒
        assertValidation(() -> service.create(fixed(IS, IS), ACTOR));
        ShopCoupon ok = service.create(fixed(IS.minusSeconds(3600), IE), ACTOR);
        assertThat(ok.getValidStartAt()).isEqualTo(IS.minusSeconds(3600));
        assertThat(ok.getValidDays()).isNull();
    }

    @Test
    void relativeRequiresValidDays() {
        CouponDraft noDays = new CouponDraft(CouponKind.DISCOUNT, "Diskon", "Discount", 10_000L, 0L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, IS, IE, CouponValidMode.RELATIVE,
                null, null, null, null, 1);
        assertValidation(() -> service.create(noDays, ACTOR));
        CouponDraft zero = new CouponDraft(CouponKind.DISCOUNT, "Diskon", "Discount", 10_000L, 0L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, IS, IE, CouponValidMode.RELATIVE,
                null, null, 0, null, 1);
        assertValidation(() -> service.create(zero, ACTOR));
    }

    @Test
    void issuePeriodMustBeOrdered() {
        CouponDraft d = new CouponDraft(CouponKind.DISCOUNT, "Diskon", "Discount", 10_000L, 0L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, IE, IS, CouponValidMode.RELATIVE,
                null, null, 7, null, 1);
        assertValidation(() -> service.create(d, ACTOR));
    }

    @Test
    void scopeValuesValidated() {
        CouponDraft base = draft(CouponKind.DISCOUNT, 10_000, 0);
        assertValidation(() -> service.create(with(base, CouponScopeType.ALL, List.of("MAKANAN")), ACTOR));
        assertValidation(() -> service.create(with(base, CouponScopeType.CATEGORY, List.of()), ACTOR));
        assertValidation(() -> service.create(with(base, CouponScopeType.CATEGORY, List.of("BOGUS")), ACTOR));
        ShopCoupon cat = service.create(with(base, CouponScopeType.CATEGORY, List.of("MAKANAN", "MAKANAN", "CAMILAN")), ACTOR);
        assertThat(cat.getScopeValues()).containsExactly("MAKANAN", "CAMILAN");

        when(products.findExistingPublicTokens(anyCollection())).thenReturn(Set.of("p1"));
        assertValidation(() -> service.create(with(base, CouponScopeType.PRODUCT, List.of("p1", "p2")), ACTOR));
        assertThat(service.create(with(base, CouponScopeType.PRODUCT, List.of("p1")), ACTOR).getScopeValues())
                .containsExactly("p1");

        List<String> tooMany = java.util.stream.IntStream.range(0, 201).mapToObj(i -> "p" + i).toList();
        assertValidation(() -> service.create(with(base, CouponScopeType.PRODUCT, tooMany), ACTOR));
    }

    @Test
    void namesRequiredAndBounded() {
        CouponDraft blank = new CouponDraft(CouponKind.DISCOUNT, " ", "Discount", 10_000L, 0L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, IS, IE, CouponValidMode.RELATIVE,
                null, null, 7, null, 1);
        assertValidation(() -> service.create(blank, ACTOR));
        CouponDraft longName = new CouponDraft(CouponKind.DISCOUNT, "x".repeat(61), "Discount", 10_000L, 0L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, IS, IE, CouponValidMode.RELATIVE,
                null, null, 7, null, 1);
        assertValidation(() -> service.create(longName, ACTOR));
    }

    @Test
    void quantityAndPerUserLimit() {
        CouponDraft zeroTotal = new CouponDraft(CouponKind.DISCOUNT, "Diskon", "Discount", 10_000L, 0L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, IS, IE, CouponValidMode.RELATIVE,
                null, null, 7, 0, 1);
        assertValidation(() -> service.create(zeroTotal, ACTOR));
        assertValidation(() -> service.create(acquire(CouponAcquireType.CLAIM, 0), ACTOR));
        assertThat(service.create(acquire(CouponAcquireType.CLAIM, null), ACTOR).getPerUserLimit()).isEqualTo(1);
    }

    @Test
    void newbieForcesPerUserOneAndLocksBeforeOverlapCheck() {
        when(coupons.existsActiveNewbieOverlapping(IS, IE)).thenReturn(false);
        ShopCoupon c = service.create(acquire(CouponAcquireType.NEWBIE, 5), ACTOR);
        assertThat(c.getPerUserLimit()).isEqualTo(1);
        var order = org.mockito.Mockito.inOrder(newbieLock, coupons);
        order.verify(newbieLock).acquire();
        order.verify(coupons).existsActiveNewbieOverlapping(IS, IE);
        order.verify(coupons).save(any(ShopCoupon.class));
    }

    @Test
    void newbieOverlapConflicts() {
        when(coupons.existsActiveNewbieOverlapping(IS, IE)).thenReturn(true);
        assertThatThrownBy(() -> service.create(acquire(CouponAcquireType.NEWBIE, 1), ACTOR))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(coupons, never()).save(any(ShopCoupon.class));
    }

    @Test
    void nonNewbieDoesNotLock() {
        service.create(acquire(CouponAcquireType.CLAIM, 1), ACTOR);
        verifyNoInteractions(newbieLock);
    }

    @Test
    void createAuditsWithActorAndSkipsWithoutActor() {
        service.create(acquire(CouponAcquireType.CLAIM, 1), ACTOR);
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_COUPON_CREATED), eq("SHOP_COUPON"), eq("tok"), anyString());
        audit = mock(AdminAuditService.class);
        ShopTokenGenerator tokens = mock(ShopTokenGenerator.class);
        when(tokens.generate()).thenReturn("tok2");
        CouponAdminService noActor = new CouponAdminService(coupons, codes, products, ledger, userCoupons, newbieLock, tokens,
                audit, Clock.fixed(NOW, ZoneOffset.UTC));
        noActor.create(acquire(CouponAcquireType.CLAIM, 1), null);
        verifyNoInteractions(audit);
    }

    // ---------------------------------------------------------------- 停券

    @Test
    void stopWithVoidCallsLedger() {
        existing(CouponAcquireType.CLAIM);
        when(coupons.stopIfActive(7L, true, NOW)).thenReturn(1);
        when(ledger.voidUnused(7L, NOW)).thenReturn(4);
        service.stop("ct", true, ACTOR);
        verify(coupons).stopIfActive(7L, true, NOW);
        verify(ledger).voidUnused(7L, NOW);
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_COUPON_STOPPED), eq("SHOP_COUPON"), eq("ct"), anyString());
        verify(coupons, never()).save(any(ShopCoupon.class));
    }

    @Test
    void stopWithoutVoidDoesNotCallLedger() {
        existing(CouponAcquireType.CLAIM);
        when(coupons.stopIfActive(7L, false, NOW)).thenReturn(1);
        service.stop("ct", false, ACTOR);
        verify(coupons).stopIfActive(7L, false, NOW);
        verify(ledger, never()).voidUnused(anyLong(), any());
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_COUPON_STOPPED), eq("SHOP_COUPON"), eq("ct"), anyString());
    }

    /** 并发停券：读到 ACTIVE，但条件更新影响 0 行（别人先停了）→ 幂等返回，不作废、不审计。 */
    @Test
    void stopLosingConcurrentRaceIsNoop() {
        existing(CouponAcquireType.CLAIM);
        when(coupons.stopIfActive(7L, true, NOW)).thenReturn(0);
        service.stop("ct", true, ACTOR);
        verify(ledger, never()).voidUnused(anyLong(), any());
        verifyNoInteractions(audit);
    }

    /** 已 STOPPED 再停：不发条件更新、不作废、不审计（不改 void_on_stop）。 */
    @Test
    void stopAlreadyStoppedIsIdempotent() {
        ShopCoupon c = existing(CouponAcquireType.CLAIM);
        ReflectionTestUtils.setField(c, "status", CouponStatus.STOPPED);
        service.stop("ct", true, ACTOR);
        verify(coupons, never()).stopIfActive(anyLong(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(ledger, never()).voidUnused(anyLong(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void stopUnknownCouponIsNotFound() {
        when(coupons.findByPublicToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.stop("nope", false, ACTOR)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---------------------------------------------------------------- 兑换码

    @Test
    void codesOnlyForCodeCoupons() {
        existing(CouponAcquireType.CLAIM);
        assertValidation(() -> service.createUniversalCode("ct", "PROMO11", ACTOR));
        assertValidation(() -> service.generateSingleUseCodes("ct", 5, ACTOR));
    }

    @Test
    void universalCodeNormalizedUppercase() {
        existing(CouponAcquireType.CODE);
        assertThat(service.createUniversalCode("ct", "  promo11 ", ACTOR)).isEqualTo("PROMO11");
        ArgumentCaptor<ShopCouponCode> saved = ArgumentCaptor.forClass(ShopCouponCode.class);
        verify(codes).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("PROMO11");
        assertThat(saved.getValue().isSingleUse()).isFalse();
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(ACTOR), eq(AuditActions.SHOP_COUPON_CODES_CREATED), eq("SHOP_COUPON"), eq("ct"),
                summary.capture());
        assertThat(summary.getValue()).doesNotContain("PROMO11");
    }

    @Test
    void invalidCodeFormatRejected() {
        existing(CouponAcquireType.CODE);
        assertValidation(() -> service.createUniversalCode("ct", "AB1", ACTOR));
        assertValidation(() -> service.createUniversalCode("ct", "PROMO-11", ACTOR));
        assertValidation(() -> service.createUniversalCode("ct", "X".repeat(33), ACTOR));
        assertValidation(() -> service.createUniversalCode("ct", null, ACTOR));
    }

    @Test
    void duplicateUniversalCodeConflicts() {
        existing(CouponAcquireType.CODE);
        when(codes.existsByCode("PROMO11")).thenReturn(true);
        assertThatThrownBy(() -> service.createUniversalCode("ct", "promo11", ACTOR))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void concurrentUniversalCodeUniqueViolationIs409() {
        existing(CouponAcquireType.CODE);
        when(codes.saveAndFlush(any(ShopCouponCode.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_shop_coupon_codes_code"));
        assertThatThrownBy(() -> service.createUniversalCode("ct", "PROMO11", ACTOR))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(audit);
    }

    @Test
    void concurrentSingleUseUniqueViolationIs409() {
        existing(CouponAcquireType.CODE);
        when(codes.findExistingCodes(anyCollection())).thenReturn(Set.of());
        when(codes.saveAllAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_shop_coupon_codes_code"));
        assertThatThrownBy(() -> service.generateSingleUseCodes("ct", 3, ACTOR))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(audit);
    }

    @Test
    @SuppressWarnings("unchecked")
    void singleUseCodesGeneratedFromSafeAlphabet() {
        existing(CouponAcquireType.CODE);
        when(codes.findExistingCodes(anyCollection())).thenReturn(Set.of());
        List<String> out = service.generateSingleUseCodes("ct", 50, ACTOR);
        assertThat(out).hasSize(50).doesNotHaveDuplicates()
                .allMatch(c -> c.matches("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}$"));
        ArgumentCaptor<Iterable<ShopCouponCode>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(codes).saveAllAndFlush(saved.capture());
        assertThat(saved.getValue()).hasSize(50).allMatch(ShopCouponCode::isSingleUse);
    }

    @Test
    void singleUseCountBounds() {
        assertValidation(() -> service.generateSingleUseCodes("ct", 0, ACTOR));
        assertValidation(() -> service.generateSingleUseCodes("ct", 1001, ACTOR));
    }

    @Test
    void codesWithoutActorSkipAudit() {
        existing(CouponAcquireType.CODE);
        service.createUniversalCode("ct", "PROMO11", null);
        verifyNoInteractions(audit);
    }
}

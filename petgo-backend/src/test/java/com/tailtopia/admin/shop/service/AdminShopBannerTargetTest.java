package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.shop.dto.ShopBannerForm;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.BannerTargetType;
import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponStatus;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.repository.ShopBannerRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.service.ShopBannerQueryService;
import java.time.Instant;
import java.util.Optional;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * L0（V1.4.1）：Toko banner 点击跳转的后台校验 —— 每类都校验到「App 点下去一定能落地」。
 * 另验新建 banner 自带不可枚举 token（埋点 banner_id），且 NONE ⇔ 无目标值。
 */
class AdminShopBannerTargetTest {

    private final ShopProductRepository products = Mockito.mock(ShopProductRepository.class);
    private final ShopCouponRepository coupons = Mockito.mock(ShopCouponRepository.class);
    private final ShopBannerRepository banners = Mockito.mock(ShopBannerRepository.class);
    private final AdminShopBannerService service = new AdminShopBannerService(
            banners, Mockito.mock(AdminAuditService.class), products, coupons,
            Mockito.mock(ShopBannerQueryService.class));

    private static ShopBannerForm form(String type, String value) {
        ShopBannerForm f = new ShopBannerForm();
        f.setImageKey("shop-banner/a.jpg");
        f.setTargetType(type);
        f.setTargetValue(value);
        return f;
    }

    @Test
    void emptyOrNoneMeansNotTappableAndDropsValue() {
        assertThat(service.target(form(null, "x")))
                .isEqualTo(new AdminShopBannerService.Target(BannerTargetType.NONE, null));
        assertThat(service.target(form("NONE", "https://a.b")).value()).isNull();
    }

    @Test
    void productMustExistAndBeListed() {
        Mockito.when(products.findByPublicTokenAndActiveTrue("tokA"))
                .thenReturn(Optional.of(Mockito.mock(ShopProduct.class)));
        assertThat(service.target(form("PRODUCT", " tokA ")))
                .isEqualTo(new AdminShopBannerService.Target(BannerTargetType.PRODUCT, "tokA"));
        assertThatThrownBy(() -> service.target(form("PRODUCT", "missing")))
                .isInstanceOf(AppException.class).hasMessageContaining("商品");
    }

    /** V1.4.2 shop-admin Story 3.1 · B4：品类跳转已下线，任何品类值一律拒绝（存量 banner 须改选）。 */
    @Test
    void categoryIsRetiredAndAlwaysRejected() {
        assertThatThrownBy(() -> service.target(form("CATEGORY", "CAMILAN")))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getMessage()).contains("品类"));
        assertThatThrownBy(() -> service.target(form("CATEGORY", "camilan")))
                .isInstanceOf(AppException.class);
    }

    @Test
    void externalOnlyHttpsWithHost() {
        assertThat(service.target(form("EXTERNAL", "https://tailtopia.id/promo")).value())
                .isEqualTo("https://tailtopia.id/promo");
        for (String bad : new String[] {"http://tailtopia.id", "javascript:alert(1)", "tailtopia://shop",
                "https://", ""}) {
            assertThatThrownBy(() -> service.target(form("EXTERNAL", bad)))
                    .as(bad).isInstanceOf(AppException.class);
        }
    }

    @Test
    void unknownTypeRejected() {
        // v1.4.2 起 COUPON 是合法类型（见下方 COUPON 用例），换一个真不存在的字面量
        assertThatThrownBy(() -> service.target(form("TOKO_HOME", "x"))).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.target(form("coupon", "x"))).isInstanceOf(AppException.class);
    }

    // ---------- v1.4.2 shop Story 6.1 AC7：COUPON 目标 + 排期 ----------

    private static ShopCoupon coupon(CouponAcquireType type, CouponStatus status, Instant issueEnd) {
        ShopCoupon c = ShopCoupon.create("cpn1", com.tailtopia.shop.coupon.domain.CouponKind.values()[0],
                "n", "n", 5000, 0, com.tailtopia.shop.coupon.domain.CouponScopeType.values()[0],
                java.util.List.of(), type, Instant.parse("2026-01-01T00:00:00Z"), issueEnd,
                com.tailtopia.shop.coupon.domain.CouponValidMode.values()[0], null, null, 7, null, 1);
        ReflectionTestUtils.setField(c, "status", status);
        return c;
    }

    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    @Test
    void couponMustBeActiveClaimAndNotPastIssueEnd() {
        Mockito.when(coupons.findByPublicToken("cpn1"))
                .thenReturn(Optional.of(coupon(CouponAcquireType.CLAIM, CouponStatus.ACTIVE, FUTURE)));
        assertThat(service.target(form("COUPON", " cpn1 ")))
                .isEqualTo(new AdminShopBannerService.Target(BannerTargetType.COUPON, "cpn1"));
    }

    @Test
    void couponMissingStoppedNonClaimOrExpiredIsRejected() {
        Mockito.when(coupons.findByPublicToken("missing")).thenReturn(Optional.empty());
        assertCouponRejected("missing");
        assertCouponRejected("");

        Mockito.when(coupons.findByPublicToken("cpn1"))
                .thenReturn(Optional.of(coupon(CouponAcquireType.CLAIM, CouponStatus.STOPPED, FUTURE)));
        assertCouponRejected("cpn1");
        for (CouponAcquireType t : new CouponAcquireType[] {CouponAcquireType.NEWBIE, CouponAcquireType.CODE}) {
            Mockito.when(coupons.findByPublicToken("cpn1"))
                    .thenReturn(Optional.of(coupon(t, CouponStatus.ACTIVE, FUTURE)));
            assertCouponRejected("cpn1");
        }
        Mockito.when(coupons.findByPublicToken("cpn1")).thenReturn(Optional.of(
                coupon(CouponAcquireType.CLAIM, CouponStatus.ACTIVE, Instant.parse("2026-02-01T00:00:00Z"))));
        assertCouponRejected("cpn1");
    }

    private void assertCouponRejected(String value) {
        assertThatThrownBy(() -> service.target(form("COUPON", value)))
                .as(value).isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("admin.err.banner.targetCoupon"));
    }

    @Test
    void scheduleStartMustBeBeforeEnd() {
        Instant t = Instant.parse("2026-11-05T10:00:00Z");
        ShopBannerForm f = form(null, null);
        f.setStartAt(t);
        f.setEndAt(t);
        assertThatThrownBy(() -> service.create(f, 1L)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getMessageCode()).isEqualTo("admin.err.banner.scheduleInvalid"));
        f.setEndAt(t.minusSeconds(1));
        assertThatThrownBy(() -> service.create(f, 1L)).isInstanceOf(AppException.class);
        Mockito.verify(banners, Mockito.never()).save(Mockito.any());

        f.setEndAt(t.plusSeconds(1));
        ShopBanner b = service.create(f, 1L);
        assertThat(b.getStartAt()).isEqualTo(t);
        assertThat(b.getEndAt()).isEqualTo(t.plusSeconds(1));
        f.setStartAt(null);
        f.setEndAt(null);
        ShopBanner open = service.create(f, 1L);
        assertThat(open.getStartAt()).isNull();
        assertThat(open.getEndAt()).isNull();
    }

    @Test
    void newBannerHasOpaqueTokenAndRetargetKeepsNoneInvariant() {
        ShopBanner a = ShopBanner.create("k1", 1200, 400, 0);
        ShopBanner b = ShopBanner.create("k2", 1200, 400, 0);
        assertThat(a.getPublicToken()).hasSize(22).isNotEqualTo(b.getPublicToken());
        assertThat(a.getTargetType()).isEqualTo(BannerTargetType.NONE);
        a.retarget(BannerTargetType.EXTERNAL, "https://x.y");
        assertThat(a.getTargetValue()).isEqualTo("https://x.y");
        a.retarget(BannerTargetType.NONE, "https://x.y");
        assertThat(a.getTargetValue()).isNull();
    }
}

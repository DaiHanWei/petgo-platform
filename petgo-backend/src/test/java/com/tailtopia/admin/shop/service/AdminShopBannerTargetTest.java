package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.shop.dto.ShopBannerForm;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.BannerTargetType;
import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.repository.ShopBannerRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * L0（V1.4.1）：Toko banner 点击跳转的后台校验 —— 每类都校验到「App 点下去一定能落地」。
 * 另验新建 banner 自带不可枚举 token（埋点 banner_id），且 NONE ⇔ 无目标值。
 */
class AdminShopBannerTargetTest {

    private final ShopProductRepository products = Mockito.mock(ShopProductRepository.class);
    private final AdminShopBannerService service = new AdminShopBannerService(
            Mockito.mock(ShopBannerRepository.class), Mockito.mock(AdminAuditService.class), products);

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

    @Test
    void categoryMustBeKnownLiteral() {
        assertThat(service.target(form("CATEGORY", "CAMILAN")).value()).isEqualTo("CAMILAN");
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
        assertThatThrownBy(() -> service.target(form("COUPON", "x"))).isInstanceOf(AppException.class);
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

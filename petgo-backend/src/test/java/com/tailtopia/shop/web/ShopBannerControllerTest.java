package com.tailtopia.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.domain.BannerTargetType;
import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.dto.ShopBannerView;
import com.tailtopia.shop.repository.ShopBannerRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * L0（V1.4.1）：App 拉 banner 时下发跳转目标；商品类目标每次下发前复查在售，
 * 已下架 / 已删除就按「不可点」下发（后台只在保存那一刻校验过）。
 */
class ShopBannerControllerTest {

    private final ShopBannerRepository banners = Mockito.mock(ShopBannerRepository.class);
    private final ShopImageUrlResolver imageUrls = Mockito.mock(ShopImageUrlResolver.class);
    private final ShopProductRepository products = Mockito.mock(ShopProductRepository.class);
    private final ShopBannerController controller = new ShopBannerController(banners, imageUrls, products);

    private ShopBannerView current(BannerTargetType type, String value) {
        ShopBanner b = ShopBanner.create("shop-banner/a.jpg", 1600, 900, 0);
        b.retarget(type, value);
        Mockito.when(banners.findFirstByActiveTrueOrderBySortWeightDescIdDesc()).thenReturn(Optional.of(b));
        Mockito.when(imageUrls.publicUrl("shop-banner/a.jpg")).thenReturn("https://cdn.example/a.jpg");
        ShopBannerView v = controller.current().getBody();
        assertThat(v).isNotNull();
        assertThat(v.bannerId()).isEqualTo(b.getPublicToken());
        return v;
    }

    @Test
    void listedProductStaysTappable() {
        Mockito.when(products.findByPublicTokenAndActiveTrue("tokA"))
                .thenReturn(Optional.of(Mockito.mock(ShopProduct.class)));
        ShopBannerView v = current(BannerTargetType.PRODUCT, "tokA");
        assertThat(v.targetType()).isEqualTo("PRODUCT");
        assertThat(v.targetValue()).isEqualTo("tokA");
    }

    @Test
    void delistedProductIsServedAsNotTappable() {
        Mockito.when(products.findByPublicTokenAndActiveTrue("tokA")).thenReturn(Optional.empty());
        ShopBannerView v = current(BannerTargetType.PRODUCT, "tokA");
        assertThat(v.targetType()).isEqualTo("NONE");
        assertThat(v.targetValue()).isNull();
    }

    @Test
    void otherTargetsPassThroughWithoutProductLookup() {
        assertThat(current(BannerTargetType.CATEGORY, "CAMILAN").targetValue()).isEqualTo("CAMILAN");
        assertThat(current(BannerTargetType.EXTERNAL, "https://a.b").targetType()).isEqualTo("EXTERNAL");
        assertThat(current(BannerTargetType.NONE, null).targetValue()).isNull();
        Mockito.verifyNoInteractions(products);
    }
}

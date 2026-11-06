package com.tailtopia.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.domain.BannerTargetType;
import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.dto.ShopBannerListView;
import com.tailtopia.shop.dto.ShopBannerView;
import com.tailtopia.shop.service.ShopBannerQueryService;
import com.tailtopia.shop.service.ShopBannerQueryService.LiveBanner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

/**
 * L0（V1.4.1 → v1.4.2 shop Story 6.1）：两个 banner 端点都只走 {@link ShopBannerQueryService#liveBanners}。
 * 旧端点取第一张、对旧版 App 做兼容转换（COUPON → NONE、CATEGORY 保持）；新端点 {@code items} 形状。
 */
class ShopBannerControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private final ShopBannerQueryService query = Mockito.mock(ShopBannerQueryService.class);
    private final ShopBannerController controller =
            new ShopBannerController(query, Clock.fixed(NOW, ZoneOffset.UTC));

    private static ShopBanner banner(BannerTargetType type, String value) {
        ShopBanner b = ShopBanner.create("shop-banner/a.jpg", 1600, 900, 0);
        b.retarget(type, value);
        return b;
    }

    /** 用真实的 select 纯函数产出下发形态（与生产同一份转换），只 mock 掉取库部分。 */
    private void live(ShopBanner... bs) {
        List<LiveBanner> l = ShopBannerQueryService.select(List.of(bs), Set.of("tokA"), Set.of("cpn1"),
                k -> "https://cdn.example/" + k, ShopBannerQueryService.MAX_BANNERS);
        Mockito.when(query.liveBanners(NOW)).thenReturn(l);
    }

    private ShopBannerView legacy() {
        var resp = controller.current();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @Test
    void legacyServesTheFirstLiveBanner() {
        ShopBanner first = banner(BannerTargetType.PRODUCT, "tokA");
        live(first, banner(BannerTargetType.EXTERNAL, "https://a.b"));
        ShopBannerView v = legacy();
        assertThat(v.bannerId()).isEqualTo(first.getPublicToken());
        assertThat(v.targetType()).isEqualTo("PRODUCT");
        assertThat(v.targetValue()).isEqualTo("tokA");
        assertThat(v.imageUrl()).isEqualTo("https://cdn.example/shop-banner/a.jpg");
        assertThat(v.imageW()).isEqualTo(1600);
    }

    /**
     * AC6.3（按设计改断言，原 delistedProductIsServedAsNotTappable）：目标商品下架的 banner
     * 不再「降级为不可点」，而是<b>整张不展示、轮到下一张</b>。
     */
    @Test
    void delistedProductIsServedAsNotTappable() {
        ShopBanner delisted = banner(BannerTargetType.PRODUCT, "gone");
        ShopBanner next = banner(BannerTargetType.EXTERNAL, "https://a.b");
        live(delisted, next);
        ShopBannerView v = legacy();
        assertThat(v.bannerId()).as("下架商品的那张被剔除，轮到下一张").isEqualTo(next.getPublicToken());
        assertThat(v.targetType()).isEqualTo("EXTERNAL");
    }

    @Test
    void legacyDowngradesCouponToNoneAndKeepsCategory() {
        ShopBanner coupon = banner(BannerTargetType.COUPON, "cpn1");
        live(coupon);
        ShopBannerView v = legacy();
        assertThat(v.bannerId()).isEqualTo(coupon.getPublicToken());
        assertThat(v.targetType()).as("旧版不认识领券 → 不可点").isEqualTo("NONE");
        assertThat(v.targetValue()).isNull();

        live(banner(BannerTargetType.CATEGORY, "CAMILAN"));
        ShopBannerView c = legacy();
        assertThat(c.targetType()).as("旧版支持品类跳转，保持 CATEGORY，不出现 TOKO_HOME").isEqualTo("CATEGORY");
        assertThat(c.targetValue()).isEqualTo("CAMILAN");
    }

    @Test
    void legacyNoBannerIs204() {
        Mockito.when(query.liveBanners(NOW)).thenReturn(List.of());
        assertThat(controller.current().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.current().getBody()).isNull();
    }

    @Test
    void listShapeMapsCategoryToTokoHomeAndKeepsOrder() {
        ShopBanner a = banner(BannerTargetType.CATEGORY, "CAMILAN");
        ShopBanner b = banner(BannerTargetType.COUPON, "cpn1");
        ShopBanner c = banner(BannerTargetType.NONE, null);
        live(a, b, c);
        ShopBannerListView v = controller.list();
        assertThat(v.items()).extracting(ShopBannerView::bannerId)
                .containsExactly(a.getPublicToken(), b.getPublicToken(), c.getPublicToken());
        assertThat(v.items().get(0).targetType()).isEqualTo("TOKO_HOME");
        assertThat(v.items().get(0).targetValue()).isNull();
        assertThat(v.items().get(1).targetType()).isEqualTo("COUPON");
        assertThat(v.items().get(1).targetValue()).isEqualTo("cpn1");
        assertThat(v.items().get(2).targetType()).isEqualTo("NONE");
    }

    @Test
    void listEmptyIsEmptyArrayNot204() {
        Mockito.when(query.liveBanners(NOW)).thenReturn(List.of());
        assertThat(controller.list().items()).isNotNull().isEmpty();
    }
}

package com.tailtopia.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.domain.BannerTargetType;
import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.repository.ShopBannerRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.service.ShopBannerQueryService.LiveBanner;
import com.tailtopia.shared.media.MediaProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

/** L0（v1.4.2 shop Story 6.1 AC4）：banner 选取单一来源的纯函数与批量查询行为。 */
class ShopBannerQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Function<String, String> URL = k -> "https://cdn/" + k;

    private static ShopBanner banner(String key, BannerTargetType type, String value) {
        ShopBanner b = ShopBanner.create(key, 1200, 400, 0);
        b.retarget(type, value);
        return b;
    }

    private static List<String> keys(List<LiveBanner> l) {
        return l.stream().map(x -> x.banner().getImageKey()).toList();
    }

    @Test
    @DisplayName("商品不在售 / 券不可领 → 整张剔除；其余按原顺序保留")
    void invalidTargetsAreDropped() {
        List<ShopBanner> c = List.of(
                banner("p-ok", BannerTargetType.PRODUCT, "tokA"),
                banner("p-gone", BannerTargetType.PRODUCT, "tokB"),
                banner("c-ok", BannerTargetType.COUPON, "cpn1"),
                banner("c-gone", BannerTargetType.COUPON, "cpn2"),
                banner("ext", BannerTargetType.EXTERNAL, "https://a.b"),
                banner("none", BannerTargetType.NONE, null));
        List<LiveBanner> out = ShopBannerQueryService.select(c, Set.of("tokA"), Set.of("cpn1"), URL, 6);
        assertThat(keys(out)).containsExactly("p-ok", "c-ok", "ext", "none");
        assertThat(out.get(0).wireTargetType()).isEqualTo("PRODUCT");
        assertThat(out.get(0).wireTargetValue()).isEqualTo("tokA");
        assertThat(out.get(1).wireTargetType()).isEqualTo("COUPON");
        assertThat(out.get(1).wireTargetValue()).isEqualTo("cpn1");
        assertThat(out.get(2).wireTargetValue()).isEqualTo("https://a.b");
        assertThat(out.get(3).wireTargetValue()).isNull();
        assertThat(out.get(0).imageUrl()).isEqualTo("https://cdn/p-ok");
    }

    @Test
    @DisplayName("CATEGORY → 下发 TOKO_HOME 且值为空（库里原值不动）")
    void categoryBecomesTokoHome() {
        ShopBanner b = banner("cat", BannerTargetType.CATEGORY, "CAMILAN");
        LiveBanner l = ShopBannerQueryService.select(List.of(b), Set.of(), Set.of(), URL, 6).get(0);
        assertThat(l.wireTargetType()).isEqualTo("TOKO_HOME");
        assertThat(l.wireTargetValue()).isNull();
        assertThat(b.getTargetValue()).isEqualTo("CAMILAN");
    }

    @Test
    @DisplayName("图片 URL 拼不出（null / 空白）→ 整张剔除")
    void unresolvableImageIsDropped() {
        List<ShopBanner> c = List.of(banner("a", BannerTargetType.NONE, null),
                banner("b", BannerTargetType.NONE, null), banner("c", BannerTargetType.NONE, null));
        Function<String, String> url = k -> switch (k) {
            case "a" -> null;
            case "b" -> " ";
            default -> "https://cdn/" + k;
        };
        assertThat(keys(ShopBannerQueryService.select(c, Set.of(), Set.of(), url, 6))).containsExactly("c");
    }

    @Test
    @DisplayName("上限 6 张、顺序保持（剔除后再截断）")
    void capsAtSixAfterFiltering() {
        List<ShopBanner> c = new ArrayList<>();
        c.add(banner("gone", BannerTargetType.PRODUCT, "x"));
        for (int i = 0; i < 8; i++) {
            c.add(banner("b" + i, BannerTargetType.NONE, null));
        }
        assertThat(keys(ShopBannerQueryService.select(c, Set.of(), Set.of(), URL, 6)))
                .containsExactly("b0", "b1", "b2", "b3", "b4", "b5");
    }

    @Test
    @DisplayName("排期边界：start == now 有效、end == now 无效、两端 null 一直有效")
    void scheduleBoundaries() {
        ShopBanner b = ShopBanner.create("k", 1, 1, 0);
        assertThat(b.isScheduledAt(NOW)).isTrue();
        b.schedule(NOW, NOW.plusSeconds(60));
        assertThat(b.isScheduledAt(NOW)).isTrue();
        assertThat(b.isScheduledAt(NOW.minusMillis(1))).isFalse();
        b.schedule(NOW.minusSeconds(60), NOW);
        assertThat(b.isScheduledAt(NOW)).isFalse();
        assertThat(b.isScheduledAt(NOW.minusMillis(1))).isTrue();
        b.schedule(null, NOW.plusSeconds(1));
        assertThat(b.isScheduledAt(Instant.EPOCH)).isTrue();
        b.schedule(NOW, null);
        assertThat(b.isScheduledAt(Instant.parse("2099-01-01T00:00:00Z"))).isTrue();
    }

    private ShopBannerQueryService service(ShopBannerRepository banners, ShopProductRepository products,
            ShopCouponRepository coupons) {
        MediaProperties mp = new MediaProperties();
        mp.getOss().setCdnBaseUrl("https://cdn.test");
        return new ShopBannerQueryService(banners, products, coupons, new ShopImageUrlResolver(mp));
    }

    @Test
    @DisplayName("无 PRODUCT / COUPON 目标时不发批量查询；有时各一次")
    void batchLookupsOnlyWhenNeeded() {
        ShopBannerRepository banners = Mockito.mock(ShopBannerRepository.class);
        ShopProductRepository products = Mockito.mock(ShopProductRepository.class);
        ShopCouponRepository coupons = Mockito.mock(ShopCouponRepository.class);
        Mockito.when(banners.findLiveCandidates(eq(NOW), any(Pageable.class))).thenReturn(List.of(
                banner("ext", BannerTargetType.EXTERNAL, "https://a.b"),
                banner("cat", BannerTargetType.CATEGORY, "CAMILAN")));
        assertThat(service(banners, products, coupons).liveBanners(NOW)).hasSize(2);
        Mockito.verifyNoInteractions(products, coupons);

        Mockito.when(banners.findLiveCandidates(eq(NOW), any(Pageable.class))).thenReturn(List.of(
                banner("p1", BannerTargetType.PRODUCT, "tokA"),
                banner("p2", BannerTargetType.PRODUCT, "tokB"),
                banner("c1", BannerTargetType.COUPON, "cpn1")));
        Mockito.when(products.findActivePublicTokens(Set.of("tokA", "tokB"))).thenReturn(Set.of("tokB"));
        Mockito.when(coupons.findClaimableTokens(Set.of("cpn1"), NOW)).thenReturn(Set.of());
        List<LiveBanner> out = service(banners, products, coupons).liveBanners(NOW);
        assertThat(keys(out)).containsExactly("p2");
        Mockito.verify(products, Mockito.times(1)).findActivePublicTokens(any());
        Mockito.verify(coupons, Mockito.times(1)).findClaimableTokens(any(), eq(NOW));
    }

    @Test
    @DisplayName("候选查询限量 30 行")
    void candidatesAreCappedAtThirty() {
        ShopBannerRepository banners = Mockito.mock(ShopBannerRepository.class);
        Mockito.when(banners.findLiveCandidates(eq(NOW), any(Pageable.class))).thenReturn(List.of());
        assertThat(service(banners, Mockito.mock(ShopProductRepository.class),
                Mockito.mock(ShopCouponRepository.class)).liveBanners(NOW)).isEmpty();
        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        Mockito.verify(banners).findLiveCandidates(eq(NOW), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(30);
        assertThat(captor.getValue().getPageNumber()).isZero();
    }
}

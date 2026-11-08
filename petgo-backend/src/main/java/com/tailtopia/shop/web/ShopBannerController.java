package com.tailtopia.shop.web;

import com.tailtopia.shop.domain.BannerTargetType;
import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.dto.ShopBannerListView;
import com.tailtopia.shop.dto.ShopBannerView;
import com.tailtopia.shop.service.ShopBannerQueryService;
import com.tailtopia.shop.service.ShopBannerQueryService.LiveBanner;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Toko 顶部 banner 只读端点（2026-08-27；v1.4.2 shop Story 6.1 起多张轮播）。
 *
 * <p>🔴 <b>对游客放行</b>，与商品列表同理（FR-93A）：banner 位于转化漏斗最上层，
 * 用登录墙拦住它没有任何意义 —— 需在 {@code SecurityConfig} 一并放行（两条路径各一条）。
 *
 * <p>🔴 两个端点<b>都只调 {@link ShopBannerQueryService#liveBanners}</b>（单一来源）：
 * 排期、目标有效、图片 URL 的判定不在本类重复。
 *
 * <p><b>只读</b>：配置走后台（{@code /admin/shop/banners}），本端点不提供任何写入。
 */
@RestController
public class ShopBannerController {

    private final ShopBannerQueryService query;
    private final Clock clock;

    @Autowired
    public ShopBannerController(ShopBannerQueryService query) {
        this(query, Clock.systemUTC());
    }

    ShopBannerController(ShopBannerQueryService query, Clock clock) {
        this.query = query;
        this.clock = clock;
    }

    /**
     * 新版 App 首页轮播（AC5）：最多 6 张，{@code CATEGORY} 下发为 {@code TOKO_HOME}；无 → {@code {"items": []}}。
     */
    @GetMapping("/api/v1/shop/banners")
    public ShopBannerListView list() {
        List<ShopBannerView> items = query.liveBanners(clock.instant()).stream()
                .map(l -> view(l.banner(), l.imageUrl(), l.wireTargetType(), l.wireTargetValue()))
                .toList();
        return new ShopBannerListView(items);
    }

    /**
     * 旧版 App（单张，AC6）：返回新列表的<b>第一张</b>，下发形状不变。
     *
     * <p>🔴 <b>没有可展示的 banner 时返回 204 No Content</b>（同 V1.4.1）：404 是错的（端点存在，只是此刻没内容），
     * 200 + 空对象会让客户端去猜「哪个字段为空才算没有」。拼不出 URL 的 banner 已在单一来源里剔除。
     *
     * <p>对旧版的兼容转换：{@code COUPON} → {@code NONE}（旧版不认识领券，按不可点渲染）；
     * {@code CATEGORY} 保持原类型与原值（旧版支持品类跳转）；{@code TOKO_HOME} 不出现在本端点。
     * 行为变化（按设计）：目标商品下架的 banner 整张不展示、轮到下一张；排期外的不再返回。
     */
    @GetMapping("/api/v1/shop/banner")
    public ResponseEntity<ShopBannerView> current() {
        List<LiveBanner> live = query.liveBanners(clock.instant());
        if (live.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        LiveBanner first = live.get(0);
        ShopBanner b = first.banner();
        BannerTargetType type = b.getTargetType() == null ? BannerTargetType.NONE : b.getTargetType();
        String wireType;
        String wireValue;
        switch (type) {
            case COUPON, NONE -> {
                wireType = BannerTargetType.NONE.name();
                wireValue = null;
            }
            case CATEGORY -> {
                wireType = BannerTargetType.CATEGORY.name();
                wireValue = b.getTargetValue();
            }
            default -> {
                wireType = first.wireTargetType();
                wireValue = first.wireTargetValue();
            }
        }
        return ResponseEntity.ok(view(b, first.imageUrl(), wireType, wireValue));
    }

    private static ShopBannerView view(ShopBanner b, String url, String type, String value) {
        return new ShopBannerView(url, b.getImageW(), b.getImageH(), b.getPublicToken(), type, value);
    }
}

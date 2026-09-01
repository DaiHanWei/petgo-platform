package com.tailtopia.shop.web;

import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.dto.ShopBannerView;
import com.tailtopia.shop.repository.ShopBannerRepository;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Toko 顶部 banner 只读端点（2026-08-27）。
 *
 * <p>🔴 <b>对游客放行</b>，与商品列表同理（FR-93A）：banner 位于转化漏斗最上层，
 * 用登录墙拦住它没有任何意义 —— 需在 {@code SecurityConfig} 一并放行。
 *
 * <p><b>只读</b>：配置走后台（{@code /admin/shop/banners}），本端点不提供任何写入。
 */
@RestController
@RequestMapping("/api/v1/shop/banner")
public class ShopBannerController {

    private final ShopBannerRepository banners;
    private final ShopImageUrlResolver imageUrls;

    public ShopBannerController(ShopBannerRepository banners, ShopImageUrlResolver imageUrls) {
        this.banners = banners;
        this.imageUrls = imageUrls;
    }

    /**
     * 当前该展示的那一张 banner。
     *
     * <p>🔴 <b>没有可展示的 banner 时返回 204 No Content</b>，而不是 200 + 空对象或 404：
     * <ul>
     *   <li>404 是错的 —— 这个端点本身存在，只是此刻没有内容，客户端不该当成异常来记；
     *   <li>200 + 空对象会让客户端必须去判断"哪个字段为空才算没有"，判据散落在客户端。
     * </ul>
     * 204 让"没有 banner"成为一个明确的、无歧义的状态。
     *
     * <p>🔴 <b>拼不出 URL 时同样按 204 处理</b>（CDN base 未配置）：
     * 此时返回一个 {@code imageUrl=null} 的对象等于把一个必然显示不出来的空壳交给客户端 ——
     * 客户端只能再判一次 null，而判漏了就是首屏一块裂图。服务端在这里判掉，
     * 客户端就只有"有/没有"两种情况。
     */
    @GetMapping
    public ResponseEntity<ShopBannerView> current() {
        Optional<ShopBanner> picked = banners.findFirstByActiveTrueOrderBySortWeightDescIdDesc();
        if (picked.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        ShopBanner b = picked.get();
        String url = imageUrls.publicUrl(b.getImageKey());
        if (url == null || url.isBlank()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new ShopBannerView(url, b.getImageW(), b.getImageH()));
    }
}

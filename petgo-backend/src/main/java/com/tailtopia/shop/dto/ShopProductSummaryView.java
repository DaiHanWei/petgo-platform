package com.tailtopia.shop.dto;

import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.Species;

/**
 * 商品列表项视图（Story 1.1）。列表只给卡片所需字段，详情走 {@link ShopProductDetailView}。
 *
 * <p>🔴 只暴露 {@code token}，绝不暴露自增 id（NFR-3）。
 * {@code mainImageKey} 是 OSS objectKey <b>不是 URL</b>——保留它是因为后台表单（Story 1.3）依赖该契约。
 * {@code mainImageUrl} 是 Story 1.6 追加的<b>派生</b>字段：公开桶 CDN 全 URL，供 App 直接显示。
 * 🔴 非签名 URL——商品目录图属公开信息（非 PII），走公开桶，不需要也不存在读侧签名机制。
 * CDN base 未配置时为 {@code null}，前端须降级到占位图。
 * {@code minPrice} 为该商品全部 SKU 的最低价（最小币种单位）；无 SKU 时为 {@code null}。
 */
public record ShopProductSummaryView(
        String token,
        String name,
        String brand,
        ProductCategory category,
        String mainImageKey,
        String mainImageUrl,
        Species species,
        Long minPrice) {
}

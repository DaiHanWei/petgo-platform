package com.tailtopia.shop.dto;

import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.Species;

/**
 * 商品列表项视图（Story 1.1）。列表只给卡片所需字段，详情走 {@link ShopProductDetailView}。
 *
 * <p>🔴 只暴露 {@code token}，绝不暴露自增 id（NFR-3）。
 * {@code mainImageKey} 是 OSS objectKey <b>不是 URL</b>——签名 URL 由前端换取，绝不入库入日志（NFR-5）。
 * {@code minPrice} 为该商品全部 SKU 的最低价（最小币种单位）；无 SKU 时为 {@code null}。
 */
public record ShopProductSummaryView(
        String token,
        String name,
        String brand,
        ProductCategory category,
        String mainImageKey,
        Species species,
        Long minPrice) {
}

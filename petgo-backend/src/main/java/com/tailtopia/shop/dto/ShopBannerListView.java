package com.tailtopia.shop.dto;

import java.util.List;

/**
 * {@code GET /api/v1/shop/banners} 响应（v1.4.2 shop Story 6.1 AC5，D-16）：{@code {"items": [...]}}，最多 6 张。
 *
 * <p>元素复用 {@link ShopBannerView}（字段名不变）；{@code targetType ∈ NONE | PRODUCT | TOKO_HOME | EXTERNAL | COUPON}。
 * 无生效 banner → {@code items = []}（App 整块不渲染）。
 */
public record ShopBannerListView(List<ShopBannerView> items) {
}

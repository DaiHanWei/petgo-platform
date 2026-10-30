package com.tailtopia.shop.dto;

/**
 * Toko 顶部 banner 视图（2026-08-27）。
 *
 * <p>V1.4.1 契约变更：加 {@code bannerId}（不可枚举 token，埋点 banner_id）与点击跳转
 * {@code targetType}（NONE / PRODUCT / CATEGORY / EXTERNAL）+ {@code targetValue}（NONE 时为 null）。
 * 旧版客户端忽略这三个字段，仍按纯展示渲染。
 *
 * <p>{@code imageW / imageH} 是原始像素，客户端按屏宽算高度，避免图到达前后布局跳动。
 * 🔴 与商品同一口径：只给原始宽高，不给比例、不给算好的高度 ——
 * 高度依赖屏宽，服务端算不了。
 * ⚠️ 手填 objectKey 的兜底路径给不出尺寸 ⇒ 两者为 null ⇒ 客户端走默认比例。
 *
 * <p>{@code imageUrl} 为公开桶 CDN 全 URL；CDN base 未配置时为 null，客户端须降级为不渲染。
 */
public record ShopBannerView(String imageUrl, Integer imageW, Integer imageH, String bannerId,
        String targetType, String targetValue) {
}

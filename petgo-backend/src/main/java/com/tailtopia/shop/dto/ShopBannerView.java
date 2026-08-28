package com.tailtopia.shop.dto;

/**
 * Toko 顶部 banner 视图（2026-08-27）。
 *
 * <p>🔴 <b>不含任何跳转字段</b>：本版本 banner 纯展示、不可点。加一个恒为 null 的
 * link 会让客户端写出永远走不到的分支 —— 要跳转时再加字段，那是明确的契约变更。
 *
 * <p>{@code imageW / imageH} 是原始像素，客户端按屏宽算高度，避免图到达前后布局跳动。
 * 🔴 与商品同一口径：只给原始宽高，不给比例、不给算好的高度 ——
 * 高度依赖屏宽，服务端算不了。
 * ⚠️ 手填 objectKey 的兜底路径给不出尺寸 ⇒ 两者为 null ⇒ 客户端走默认比例。
 *
 * <p>{@code imageUrl} 为公开桶 CDN 全 URL；CDN base 未配置时为 null，客户端须降级为不渲染。
 */
public record ShopBannerView(String imageUrl, Integer imageW, Integer imageH) {
}

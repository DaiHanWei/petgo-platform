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
 *
 * <p>{@code mainImageW / mainImageH} 是主图<b>原始像素</b>宽高（2026-08-27）——
 * Toko 列表是两列瀑布流，卡片高度 = 列宽 × (h/w)，客户端拿它用 {@code AspectRatio}
 * 预置高度，图片解码前后不再跳动。
 * 🔴 <b>只给原始宽高，不给比例、不给算好的高度</b>：比例收敛与高度护栏依赖可视区尺寸，
 * 服务端算不了，两边都 clamp 就是双重裁切（与内容侧 Feed 同一口径）。
 * ⚠️ <b>存量商品恒为 null</b>（尺寸是上传时测的，不回填）—— 客户端占位兜底不可取消。
 */
public record ShopProductSummaryView(
        String token,
        String name,
        String brand,
        ProductCategory category,
        String mainImageKey,
        String mainImageUrl,
        Integer mainImageW,
        Integer mainImageH,
        Species species,
        Long minPrice) {
}

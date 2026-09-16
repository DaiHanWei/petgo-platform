package com.tailtopia.shop.dto;

import java.util.List;

/**
 * C 端商品列表游标分页信封（Story 4-5 · SHOP-FR-13）。
 *
 * <p>{@code {items, nextCursor, hasMore}} —— 字段命名与 {@code content.dto.FeedPageResponse} /
 * {@code CommentPageResponse} <b>逐字一致</b>。camelCase、NON_NULL：
 * {@code hasMore=false} 时 {@code nextCursor} 为 null，Jackson 整键省略。
 *
 * <p>🔴 <b>这个信封只在请求带分页参数时才出现。</b>不带参数时端点仍返回
 * 原来的全量 JSON 数组 —— 线上已发布的 App 版本期望的就是那个数组，
 * 直接改成信封会让它们解析失败（轻则只看到第一页，重则整页打不开）。
 * 详见 {@code ShopProductController.list} 的注释。
 */
public record ShopProductPageResponse(
        List<ShopProductSummaryView> items,
        String nextCursor,
        boolean hasMore) {
}

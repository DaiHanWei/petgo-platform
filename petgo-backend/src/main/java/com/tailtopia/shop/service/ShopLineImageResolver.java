package com.tailtopia.shop.service;

import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 按 SKU 批量取「商品主图」的 CDN 全 URL（2026-09-03）。
 *
 * <h2>解决的问题</h2>
 * 下单**前**的三个视图（{@code ShopProductSummaryView} / {@code CartView} /
 * {@code CheckoutPreviewView}）都带 {@code mainImageUrl}，而下单**后**的三个
 * （{@code ShopOrderDetailView} / {@code ReturnableLineView} / {@code ReturnProgressView}）
 * 一张图都没有 —— 退货时用户要靠图辨认退哪件，却只能看到斜纹占位图
 * （2026-09-03 stag 回归 P2）。本类是那三处补图的共同入口。
 *
 * <h2>🔴 为什么是读时查，而不是下单时把 key 快照进订单行</h2>
 * 快照要加列 + 回填历史订单，且救不了「本轮之前的所有订单」。读时查一次即可，
 * 与购物车/结算页同一条派生路径（{@link ShopImageUrlResolver#publicUrl}）。
 * ⚠️ 代价说清楚：运营事后换了商品主图，历史订单里的缩略图会跟着变。
 * 这张图的用途是**认出是哪件**，不是留证 —— 留证走的是质检照片那条私有桶链路。
 *
 * <h2>🔴 两次 findAllById，不 N+1</h2>
 * 订单/退货页动辄十几行，逐行查是十几次往返。调用方一次把 skuId 全给进来。
 */
@Component
public class ShopLineImageResolver {

    private final ShopSkuRepository skus;
    private final ShopProductRepository products;
    private final ShopImageUrlResolver imageUrls;

    public ShopLineImageResolver(ShopSkuRepository skus, ShopProductRepository products,
            ShopImageUrlResolver imageUrls) {
        this.skus = skus;
        this.products = products;
        this.imageUrls = imageUrls;
    }

    /**
     * skuId → 商品主图 CDN 全 URL。
     *
     * <p>取不到图的键**不出现在结果里**（SKU 被物理删除、商品无主图、CDN 未配置都算）——
     * 调用方一律 {@code map.get(id)} 拿 null 走占位图，不必分辨是哪一种缺失。
     */
    public Map<Long, String> mainImageUrlBySkuId(Collection<Long> skuIds) {
        List<Long> ids = skuIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ShopSku> skuRows = skus.findAllById(ids);
        Map<Long, ShopProduct> productById = products
                .findAllById(skuRows.stream().map(ShopSku::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(ShopProduct::getId, p -> p));

        Map<Long, String> out = new HashMap<>();
        for (ShopSku sku : skuRows) {
            ShopProduct p = productById.get(sku.getProductId());
            String url = p == null ? null : imageUrls.publicUrl(p.getMainImageKey());
            if (url != null) {
                out.put(sku.getId(), url);
            }
        }
        return out;
    }
}

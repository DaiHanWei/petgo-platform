package com.tailtopia.shop.order.service;

import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 电商订单的<b>卡片摘要</b>（Story 3.9）：主图 + 首个商品名/规格 + 件数。
 *
 * <p>🔴 <b>存在的理由是把 {@code OrderCenterService} 的改动压到最小</b>：那是 275 行、
 * 三线共享、无法拆分的 fan-in 聚合器（并行契约 O-1/O-4），每多注入一个仓储就多一次撞车机会。
 * 取首图要串 line → sku → product 三张表，这些细节属于 shop 模块自己，不该漏进订单中心。
 *
 * <p>🔴 主图取<b>商品当前主图</b>而非下单时快照：订单行刻意不存图（V109 无该列）。
 * 后果是运营换主图会连带改变历史订单卡的配图 —— 这是有意接受的：
 * 卡片图只是让用户认出「是哪一单」，不是交付凭证；而为它加一列快照会把图 URL 冻进订单表，
 * 图挪了位置反而变成死链。
 */
@Service
public class ShopOrderCardService {

    private final ShopOrderLineRepository lines;
    private final ShopSkuRepository skus;
    private final ShopProductRepository products;
    private final ShopImageUrlResolver images;

    public ShopOrderCardService(ShopOrderLineRepository lines, ShopSkuRepository skus,
            ShopProductRepository products, ShopImageUrlResolver images) {
        this.lines = lines;
        this.skus = skus;
        this.products = products;
        this.images = images;
    }

    /**
     * 一张电商订单卡需要的商品摘要。
     *
     * @param thumbnailUrl 首个商品主图（无图 → null，前端走占位，绝不白屏）
     * @param itemTitle    「商品名 · 规格」；无行 → null
     * @param itemCount    🔴 <b>件数不是种类数</b>（与购物车角标同口径）
     */
    public record CardInfo(String thumbnailUrl, String itemTitle, Integer itemCount) {
    }

    @Transactional(readOnly = true)
    public CardInfo of(long orderId) {
        List<ShopOrderLine> rows = lines.findByOrderIdOrderByIdAsc(orderId);
        if (rows.isEmpty()) {
            return new CardInfo(null, null, 0);
        }
        ShopOrderLine first = rows.getFirst();
        int count = rows.stream().mapToInt(ShopOrderLine::getQty).sum();
        String url = skus.findById(first.getSkuId())
                .map(ShopSku::getProductId)
                .flatMap(products::findById)
                .map(ShopProduct::getMainImageKey)
                .map(images::publicUrl)
                .orElse(null);
        return new CardInfo(url, first.getProductName() + " · " + first.getSpecName(), count);
    }

    /**
     * 批量版（v1.4.2 shop Story 6.2 AC1.5，禁 N+1）：行一次、SKU 一次、商品一次。口径同 {@link #of}。
     *
     * @return 订单 id → 卡片摘要；入参里每个 id 都有值（无行的订单为 {@code (null, null, 0)}）
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, CardInfo> ofAll(java.util.Collection<Long> orderIds) {
        java.util.Map<Long, CardInfo> out = new java.util.HashMap<>();
        if (orderIds == null || orderIds.isEmpty()) {
            return out;
        }
        java.util.Map<Long, List<ShopOrderLine>> byOrder = new java.util.LinkedHashMap<>();
        for (ShopOrderLine l : lines.findByOrderIdInOrderByOrderIdAscIdAsc(orderIds)) {
            byOrder.computeIfAbsent(l.getOrderId(), k -> new java.util.ArrayList<>()).add(l);
        }
        List<Long> firstSkuIds = byOrder.values().stream().map(rows -> rows.getFirst().getSkuId())
                .filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<Long, Long> productIdBySku = new java.util.HashMap<>();
        java.util.Map<Long, String> urlByProduct = new java.util.HashMap<>();
        if (!firstSkuIds.isEmpty()) {
            for (ShopSku sku : skus.findAllById(firstSkuIds)) {
                productIdBySku.put(sku.getId(), sku.getProductId());
            }
            List<Long> productIds = productIdBySku.values().stream().distinct().toList();
            if (!productIds.isEmpty()) {
                for (ShopProduct p : products.findAllById(productIds)) {
                    String url = images.publicUrl(p.getMainImageKey());
                    if (url != null) {
                        urlByProduct.put(p.getId(), url);
                    }
                }
            }
        }
        for (Long id : orderIds) {
            List<ShopOrderLine> rows = byOrder.get(id);
            if (rows == null || rows.isEmpty()) {
                out.put(id, new CardInfo(null, null, 0));
                continue;
            }
            ShopOrderLine first = rows.getFirst();
            int count = rows.stream().mapToInt(ShopOrderLine::getQty).sum();
            Long productId = productIdBySku.get(first.getSkuId());
            String url = productId == null ? null : urlByProduct.get(productId);
            out.put(id, new CardInfo(url, first.getProductName() + " · " + first.getSpecName(), count));
        }
        return out;
    }
}

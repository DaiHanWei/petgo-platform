package com.tailtopia.shop.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.dto.ShopProductDetailView;
import com.tailtopia.shop.dto.ShopProductSummaryView;
import com.tailtopia.shop.dto.ShopSkuView;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品只读查询（Story 1.1，FR-94 / FR-94A）。
 *
 * <p><b>只读</b>：写入（创建/编辑/上下架/库存）属 Story 1.3 / 1.5，本 Story 不提供任何写方法。
 *
 * <p>🔴 <b>对外一律按 {@code publicToken} 寻址</b>，自增 id 不出现在任何返回体中（NFR-3）。
 * 未知 token 返回 <b>404 而非 403</b>——与 {@code HealthRecordController} 同范式，防枚举探测。
 *
 * <p>🔴 <b>不引入任何缓存</b>（NFR-1 禁通用缓存层）。SKU 上限 30（C-7），直查即可。
 */
@Service
public class ShopProductQueryService {

    private final ShopProductRepository products;
    private final ShopSkuRepository skus;

    public ShopProductQueryService(ShopProductRepository products, ShopSkuRepository skus) {
        this.products = products;
        this.skus = skus;
    }

    /**
     * 商品列表（FR-93 区域③④）。仅返回已上架商品；{@code category} 为 null 时不筛选。
     *
     * <p>排序为「运营权重降序 + id 降序兜底」，同权重时顺序稳定。
     */
    @Transactional(readOnly = true)
    public List<ShopProductSummaryView> list(ProductCategory category) {
        List<ShopProduct> rows = category == null
                ? products.findByActiveTrueOrderBySortWeightDescIdDesc()
                : products.findByActiveTrueAndCategoryOrderBySortWeightDescIdDesc(category);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> minPriceByProduct = minPriceByProduct(rows);
        List<ShopProductSummaryView> out = new ArrayList<>(rows.size());
        for (ShopProduct p : rows) {
            out.add(new ShopProductSummaryView(
                    p.getPublicToken(),
                    p.getName(),
                    p.getBrand(),
                    p.getCategory(),
                    p.getMainImageKey(),
                    p.getSpecies(),
                    minPriceByProduct.get(p.getId())));
        }
        return out;
    }

    /**
     * 商品详情 + 其 SKU 列表（FR-94 / FR-94A）。
     *
     * @throws AppException 未上架或不存在 → {@code notFound}（404，非 403 —— 防枚举）
     */
    @Transactional(readOnly = true)
    public ShopProductDetailView detail(String publicToken) {
        ShopProduct p = products.findByPublicTokenAndActiveTrue(publicToken)
                .orElseThrow(() -> AppException.notFound("商品不存在"));
        List<ShopSkuView> skuViews = skus.findByProductIdOrderByIdAsc(p.getId()).stream()
                .map(s -> new ShopSkuView(
                        s.getPublicToken(),
                        s.getSpecName(),
                        s.getPrice(),
                        s.getNetWeightG(),
                        // SKU 级为空时继承商品级，前端直接展示（FR-94A / FR-104 第 1 处明示）
                        s.effectiveReturnPolicy(p.getReturnPolicy())))
                .toList();
        return new ShopProductDetailView(
                p.getPublicToken(),
                p.getName(),
                p.getBrand(),
                p.getCategory(),
                p.getMainImageKey(),
                p.getGalleryKeys(),
                p.getSpecies(),
                p.getBodySize(),
                p.getAgeStage(),
                p.getDetailHtml(),
                p.getFeedingGuide(),
                p.getShelfLifeNote(),
                p.getReturnPolicy(),
                skuViews);
    }

    /** 一次取回本页商品的全部 SKU，按商品聚出最低价——避免 N+1。 */
    private Map<Long, Long> minPriceByProduct(List<ShopProduct> rows) {
        List<Long> ids = rows.stream().map(ShopProduct::getId).toList();
        return skus.findByProductIdInOrderByIdAsc(ids).stream()
                .collect(Collectors.toMap(
                        ShopSku::getProductId,
                        ShopSku::getPrice,
                        Math::min));
    }
}

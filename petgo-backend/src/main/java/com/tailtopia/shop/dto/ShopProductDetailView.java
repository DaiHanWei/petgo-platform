package com.tailtopia.shop.dto;

import com.tailtopia.shop.domain.AgeStage;
import com.tailtopia.shop.domain.BodySize;
import com.tailtopia.shop.domain.FeedingGuideEntry;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.Species;
import java.util.List;

/**
 * 商品详情视图（Story 1.1，FR-94 全字段 + 其 SKU 列表）。
 *
 * <p>🔴 只暴露 {@code token}，绝不暴露自增 id（NFR-3）。
 * 🔴 {@code returnPolicy} 须在商品详情页明示（FR-104 三处明示的第 1 处）。
 * 🔴 图片字段一律 OSS objectKey，非 URL（NFR-5）。
 *
 * <p>⚠️ 不含库存/售罄状态——属 Story 1.2。
 */
public record ShopProductDetailView(
        String token,
        String name,
        String brand,
        ProductCategory category,
        String mainImageKey,
        String mainImageUrl,
        List<String> galleryKeys,
        List<String> galleryUrls,
        Species species,
        BodySize bodySize,
        AgeStage ageStage,
        String detailHtml,
        List<FeedingGuideEntry> feedingGuide,
        String shelfLifeNote,
        ReturnPolicy returnPolicy,
        List<ShopSkuView> skus) {
}

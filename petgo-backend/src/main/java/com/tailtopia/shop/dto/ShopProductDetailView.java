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
 *
 * <p>v1.4.2 shop Story 6.1：已下架商品不组装本视图（410 {@code product-delisted}），末尾追加评价摘要。
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
        List<ShopSkuView> skus,
        /** v1.4.2 shop Story 6.1 AC11：评价摘要（只算已发布未隐藏）。 */
        ReviewSummary reviewSummary,
        /** v1.4.2 C-9：评价列表展示开关（同 {@code reviewSummary.displayEnabled}，供 App 直接判是否渲染列表）。 */
        boolean reviewListEnabled) {

    /**
     * 评价摘要（AC11）：开关关 → {@code items = []}；开 → 最新 3 条。无评价 → {@code total = 0}、{@code averageRating = null}。
     */
    public record ReviewSummary(long total, Double averageRating, boolean displayEnabled,
            List<com.tailtopia.shop.review.dto.ShopReviewView> items) {
    }
}

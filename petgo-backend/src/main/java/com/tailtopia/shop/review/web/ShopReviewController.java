package com.tailtopia.shop.review.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.review.domain.ShopReview;
import com.tailtopia.shop.review.dto.ProductReviewsView;
import com.tailtopia.shop.review.dto.ShopReviewView;
import com.tailtopia.shop.review.service.ShopReviewService;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品详情页的评价区（Story 7.3）。
 *
 * <p>🔒 <b>对游客开放</b>：与商品详情同一策略 —— 评价是买前决策信息，
 * 拿它当登录墙会直接杀掉转化（FR-93A）。所以本控制器<b>不下发任何评价者身份</b>。
 */
@RestController
public class ShopReviewController {

    /** 详情页首屏评价条数。首版不做分页 —— SKU ≤ 30，评价量在两位数。 */
    private static final int PAGE_SIZE = 20;

    private final ShopReviewService reviews;
    private final ShopProductRepository products;
    private final ShopImageUrlResolver imageUrls;

    public ShopReviewController(ShopReviewService reviews, ShopProductRepository products,
            ShopImageUrlResolver imageUrls) {
        this.reviews = reviews;
        this.products = products;
        this.imageUrls = imageUrls;
    }

    @GetMapping("/api/v1/shop/products/{token}/reviews")
    public ProductReviewsView forProduct(@PathVariable String token,
            @RequestParam(required = false) Integer limit) {
        var product = products.findByPublicTokenAndActiveTrue(token)
                .orElseThrow(() -> AppException.notFound("商品不存在或已下架"));
        int size = limit == null || limit <= 0 ? PAGE_SIZE : Math.min(limit, PAGE_SIZE);
        List<ShopReview> published = reviews.publishedFor(product.getId(), size);
        if (published.isEmpty()) {
            // 🔴 空态：不伪造、不预填（FR-106）
            return ProductReviewsView.empty();
        }
        List<ShopReviewView> items = new ArrayList<>();
        for (ShopReview r : published) {
            items.add(ShopReviewView.published(r, imageUrls.publicUrls(r.imageKeyList())));
        }
        return new ProductReviewsView(reviews.publishedCount(product.getId()),
                reviews.averageRating(product.getId()), items);
    }
}

package com.tailtopia.shop.review.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.review.dto.ProductReviewsView;
import com.tailtopia.shop.review.service.ProductReviewQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品详情页的评价区（Story 7.3；v1.4.2 shop Story 6.1 扩展为全部评价页的游标分页）。
 *
 * <p>🔒 <b>对游客开放</b>：与商品详情同一策略 —— 评价是买前决策信息，
 * 拿它当登录墙会直接杀掉转化（FR-93A）。评价者只下发昵称（C-9），不下发头像 / 用户 token。
 *
 * <p>商品不存在或已下架 → 404（v1.4.2 起详情是 410，但评价接口保持 404：App 先请求详情，已在详情处分流）。
 */
@RestController
public class ShopReviewController {

    private final ProductReviewQueryService reviews;
    private final ShopProductRepository products;

    public ShopReviewController(ProductReviewQueryService reviews, ShopProductRepository products) {
        this.reviews = reviews;
        this.products = products;
    }

    /**
     * @param limit  每页条数，上限 20（同 Story 7.3）
     * @param cursor v1.4.2 可选游标（上一页的 {@code nextCursor}）；坏游标按首页
     */
    @GetMapping("/api/v1/shop/products/{token}/reviews")
    public ProductReviewsView forProduct(@PathVariable String token,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        var product = products.findByPublicTokenAndActiveTrue(token)
                .orElseThrow(() -> AppException.notFound("商品不存在或已下架"));
        // 🔴 空态：不伪造、不预填（FR-106）——无评价时 total=0、averageRating=null、items=[]
        return reviews.pageFor(product.getId(), cursor, limit);
    }
}

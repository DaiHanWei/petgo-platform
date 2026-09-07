package com.tailtopia.shop.review.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.review.domain.ShopReview;
import com.tailtopia.shop.review.dto.ShopReviewView;
import com.tailtopia.shop.review.service.ShopReviewService;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提交 / 重提评价（Story 7.1 的对外入口，供 Story 7.2 的评价页调用）。
 *
 * <p>🔴 <b>返回体里带 {@code reviewStatus}</b>：同步过滤当场出结论，
 * 前端据此渲染「已发布 / 被拦截 / 审核中」三态。被拦截时<b>保留用户输入、可直接改后重提</b>。
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeShopReviewController {

    private final ShopReviewService reviews;
    private final ShopImageUrlResolver imageUrls;

    public MeShopReviewController(ShopReviewService reviews, ShopImageUrlResolver imageUrls) {
        this.reviews = reviews;
        this.imageUrls = imageUrls;
    }

    @PostMapping("/shop-reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ShopReviewView submit(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) SubmitReviewRequest req) {
        if (req == null || req.orderToken() == null || req.orderLineId() == null) {
            throw AppException.validation("缺少订单信息");
        }
        ShopReview r = reviews.submit(currentUserId(jwt), req.orderToken(), req.orderLineId(),
                req.rating() == null ? 0 : req.rating(), req.content(), req.imageKeys());
        return ShopReviewView.mine(r, imageUrls.publicUrls(r.imageKeyList()));
    }

    /** 改后重提（被拦截时用）。🔴 覆盖原记录 —— 唯一约束在订单行上，新建必 409。 */
    @PostMapping("/shop-reviews/{id}")
    public ShopReviewView resubmit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @RequestBody(required = false) SubmitReviewRequest req) {
        if (req == null) {
            throw AppException.validation("缺少评价内容");
        }
        ShopReview r = reviews.resubmit(currentUserId(jwt), id,
                req.rating() == null ? 0 : req.rating(), req.content(), req.imageKeys());
        return ShopReviewView.mine(r, imageUrls.publicUrls(r.imageKeyList()));
    }

    @GetMapping("/shop-reviews")
    public List<ShopReviewView> mine(@AuthenticationPrincipal Jwt jwt) {
        List<ShopReviewView> out = new ArrayList<>();
        for (ShopReview r : reviews.myReviews(currentUserId(jwt))) {
            out.add(ShopReviewView.mine(r, imageUrls.publicUrls(r.imageKeyList())));
        }
        return out;
    }

    /**
     * 提交评价。
     *
     * @param rating 🔴 1–5 <b>必填</b>；[content] 与 [imageKeys] 选填
     */
    public record SubmitReviewRequest(String orderToken, Long orderLineId, Integer rating,
            String content, List<String> imageKeys) {
    }

    private static long currentUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("无效的登录凭证");
        }
    }
}

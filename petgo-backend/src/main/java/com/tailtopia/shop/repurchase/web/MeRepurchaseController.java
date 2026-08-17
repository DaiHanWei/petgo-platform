package com.tailtopia.shop.repurchase.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repurchase.domain.RepurchaseTrigger;
import com.tailtopia.shop.repurchase.dto.RecommendationView;
import com.tailtopia.shop.repurchase.dto.RepurchaseCardView;
import com.tailtopia.shop.repurchase.repository.RepurchaseTriggerRepository;
import com.tailtopia.shop.repurchase.service.ProfileRecommendationService;
import com.tailtopia.shop.repurchase.service.RepurchaseScanService;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Toko 首页区域①② 的数据（Story 6.4 / 6.5，FR-107 / FR-109 / FR-93）。
 *
 * <p>🔒 <b>两个端点都在 {@code /me} 下</b> —— FR-93 的状态矩阵里，<b>游客两区都不展示</b>。
 * 把它们做成对游客开放的接口，再靠前端不渲染，等于给游客态留了一条数据暴露路径。
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeRepurchaseController {

    private final ProfileRecommendationService recommendations;
    private final RepurchaseScanService repurchase;
    private final RepurchaseTriggerRepository triggers;
    private final ShopSkuRepository skus;
    private final ShopProductRepository products;
    private final PetProfileRepository profiles;

    public MeRepurchaseController(ProfileRecommendationService recommendations,
            RepurchaseScanService repurchase, RepurchaseTriggerRepository triggers,
            ShopSkuRepository skus, ShopProductRepository products,
            PetProfileRepository profiles) {
        this.recommendations = recommendations;
        this.repurchase = repurchase;
        this.triggers = triggers;
        this.skus = skus;
        this.products = products;
        this.profiles = profiles;
    }

    /**
     * 区域②「为我的宠物精选」（FR-107）。
     *
     * <p>🔴 未建档时返回 {@code degraded=true, missing=PROFILE, items=[]} ——
     * 前端据此<b>用建档引导卡替换整区</b>（复用 FR-0G 文案，不新建）。
     */
    @GetMapping("/shop/recommendations")
    public RecommendationView recommendations(@AuthenticationPrincipal Jwt jwt) {
        return recommendations.recommendFor(currentUserId(jwt));
    }

    /**
     * 区域①「补货提醒」（FR-109）。
     *
     * <p>🔴 <b>无触发时返回空列表，前端整区不渲染</b> —— 不是渲染一个空态。
     * 按当前 DEP-6 状态，这是上线首日的常态。
     */
    @GetMapping("/shop/repurchase-cards")
    public List<RepurchaseCardView> cards(@AuthenticationPrincipal Jwt jwt) {
        long userId = currentUserId(jwt);
        String petName = profiles.findByOwnerId(userId)
                .map(com.tailtopia.profile.domain.PetProfile::getName).orElse(null);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<RepurchaseCardView> out = new ArrayList<>();
        for (RepurchaseTrigger t : repurchase.activeTriggersFor(userId)) {
            var sku = skus.findById(t.getSkuId()).orElse(null);
            if (sku == null) {
                continue;
            }
            var product = products.findById(sku.getProductId()).orElse(null);
            if (product == null) {
                continue;
            }
            out.add(new RepurchaseCardView(t.getId(), t.getTriggerType().name(),
                    sku.getPublicToken(), product.getPublicToken(), product.getName(), petName,
                    t.getEstimatedDepletionDate(),
                    ChronoUnit.DAYS.between(today, t.getEstimatedDepletionDate())));
        }
        return RepurchaseCardView.capped(out);
    }

    /** 用户关掉一张补货卡。 */
    @PostMapping("/shop/repurchase-cards/{id}/dismiss")
    public void dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        long userId = currentUserId(jwt);
        RepurchaseTrigger t = triggers.findById(id)
                .filter(x -> x.getUserId() == userId)
                .orElseThrow(() -> AppException.notFound("触发记录不存在"));
        t.dismiss();
        triggers.save(t);
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

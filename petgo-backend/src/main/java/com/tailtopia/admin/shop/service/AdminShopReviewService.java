package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.review.domain.ReviewHideReason;
import com.tailtopia.shop.review.domain.ShopReview;
import com.tailtopia.shop.review.repository.ShopReviewRepository;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品评价的运营动作（v1.4.2 shop Story 6.1 AC9.3，D-17，PRD FR-133.6 / 后台 AB-39）。
 *
 * <p>S-2：service 归 shop 线，后台页面（隐藏按钮、展示开关页）由后台线做。
 *
 * <p>🔒 审计摘要<b>只记评价 id 与商品 token，绝不记评价正文</b>（正文可能含用户个人叙述）。
 * 隐藏只置 {@code hidden_at}，不改 {@code review_status}（可撤销；作者「我的评价」不受影响）。
 */
@Service
public class AdminShopReviewService {

    static final String AUDIT_TARGET_REVIEW = "SHOP_REVIEW";
    static final String AUDIT_TARGET_SETTINGS = "SHIPPING_SETTINGS";

    private final ShopReviewRepository reviews;
    private final ShopProductRepository products;
    private final ShippingSettingsRepository settings;
    private final AdminAuditService audit;

    public AdminShopReviewService(ShopReviewRepository reviews, ShopProductRepository products,
            ShippingSettingsRepository settings, AdminAuditService audit) {
        this.reviews = reviews;
        this.products = products;
        this.settings = settings;
        this.audit = audit;
    }

    /**
     * 隐藏（V1.4.2 shop-admin Story 3.3 · AD-16）：须带原因（J3）；不存在 → 404；已隐藏 → 幂等（不改写时刻 / 原因、不重复写审计）。
     * 不通知写评价的用户。审计摘要记原因。
     */
    @Transactional
    public void hide(long reviewId, ReviewHideReason reason, Long actorAccountId) {
        if (reason == null) {
            throw AppException.validation("请选择隐藏原因").code("admin.err.shopReview.reasonRequired");
        }
        ShopReview r = require(reviewId);
        if (r.isHidden()) {
            return;
        }
        r.hide(Instant.now(), reason, actorAccountId);
        reviews.save(r);
        audit(actorAccountId, AuditActions.SHOP_REVIEW_HIDDEN, r, "隐藏评价 reason=" + reason);
    }

    /** 撤销隐藏：不存在 → 404；未隐藏 → 幂等。 */
    @Transactional
    public void unhide(long reviewId, Long actorAccountId) {
        ShopReview r = require(reviewId);
        if (!r.isHidden()) {
            return;
        }
        r.unhide();
        reviews.save(r);
        audit(actorAccountId, AuditActions.SHOP_REVIEW_UNHIDDEN, r, "撤销隐藏评价");
    }

    /** 评价列表展示开关（{@code shipping_settings.review_display_enabled}，默认关）。 */
    @Transactional
    public void setDisplayEnabled(boolean enabled, Long actorAccountId) {
        ShippingSettings s = settings.findById(ShippingSettings.SINGLETON_ID)
                .orElseThrow(() -> AppException.notFound("配送设置不存在")
                        .code("admin.err.shopReview.settingsMissing"));
        s.setReviewDisplayEnabled(enabled);
        settings.save(s);
        // 🔴 总是写审计（actor 可为 null = 系统 / stag 发起，AdminAuditService 支持）
        audit.record(actorAccountId, AuditActions.SHOP_REVIEW_DISPLAY_TOGGLED, AUDIT_TARGET_SETTINGS,
                String.valueOf(ShippingSettings.SINGLETON_ID), enabled ? "评价列表展示：开" : "评价列表展示：关");
    }

    /** 后台评价页签只列已发布：路径里的评价须属于该商品且已发布（待审 / 拦截的不经此入口隐藏或恢复）。 */
    @Transactional(readOnly = true)
    public boolean isPublishedReviewOf(long productId, long reviewId) {
        return reviews.findById(reviewId)
                .map(r -> r.getProductId() == productId && r.isPublished()).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isDisplayEnabled() {
        return settings.findById(ShippingSettings.SINGLETON_ID)
                .map(ShippingSettings::isReviewDisplayEnabled).orElse(false);
    }

    private ShopReview require(long reviewId) {
        return reviews.findById(reviewId)
                .orElseThrow(() -> AppException.notFound("评价不存在")
                        .code("admin.err.shopReview.notFound"));
    }

    /** 🔴 总是写审计（actor 可为 null，AdminAuditService 支持），不因无操作人跳过。 */
    private void audit(Long actorAccountId, String action, ShopReview r, String verb) {
        String productToken = products.findById(r.getProductId()).map(p -> p.getPublicToken()).orElse("-");
        // 🔒 只记 id 与商品 token —— 不记 content
        audit.record(actorAccountId, action, AUDIT_TARGET_REVIEW, String.valueOf(r.getId()),
                verb + " #" + r.getId() + "（商品 " + productToken + "）");
    }
}

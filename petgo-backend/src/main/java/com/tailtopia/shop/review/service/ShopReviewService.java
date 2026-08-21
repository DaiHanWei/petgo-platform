package com.tailtopia.shop.review.service;

import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.service.ContentModerationService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.review.domain.ReviewStatus;
import com.tailtopia.shop.review.domain.ShopReview;
import com.tailtopia.shop.review.repository.ShopReviewRepository;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品评价（Story 7.1，FR-106）。
 *
 * <p>🔴 <b>复用既有内容审核机制，同步过滤</b>：提交时就走三方，
 * <b>命中即拦截、不发布</b>（对应 AB-3C 激活的处理方式）。
 * ⚠️ <b>不走「先发布后审核」</b> —— 商品评价是带图的公开内容，先发后审意味着违规内容
 * 在被发现前对所有潜在买家可见，而它旁边就是我们自己在卖的商品。
 *
 * <p>🔴 <b>被拦截不进人工队列</b>：用户停留编辑态、可改后重提（F13 输入类失败统一口径）。
 * 排一条人工队列只会让用户等一个他自己两秒就能解决的问题。
 *
 * <p>🔴 <b>三方不可用时 fail-closed 但落 {@code PENDING} 而非 {@code BLOCKED}</b>：
 * 那不是用户的错，不该让他去改一段本来没问题的文案。由定时重试自己收敛。
 */
@Service
public class ShopReviewService {

    private static final Logger log = LoggerFactory.getLogger(ShopReviewService.class);

    private final ShopReviewRepository reviews;
    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final ShopSkuRepository skus;
    private final ContentModerationService moderation;
    private final ShopImageUrlResolver imageUrls;

    public ShopReviewService(ShopReviewRepository reviews, ShopOrderRepository orders,
            ShopOrderLineRepository orderLines, ShopSkuRepository skus,
            ContentModerationService moderation, ShopImageUrlResolver imageUrls) {
        this.reviews = reviews;
        this.orders = orders;
        this.orderLines = orderLines;
        this.skus = skus;
        this.moderation = moderation;
        this.imageUrls = imageUrls;
    }

    /**
     * 提交评价。
     *
     * @return 落库的评价（{@code reviewStatus} 即同步过滤的结论）
     */
    @Transactional
    public ShopReview submit(long userId, String orderToken, long orderLineId, int rating,
            String content, List<String> imageKeys) {
        ShopOrder order = orders.findByPublicTokenAndUserId(orderToken, userId)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        // 🔴 只有已完成订单可评 —— 没收到货就能评的评价对下一个买家没有任何参考价值
        if (order.getStatus() != ShopOrderStatus.COMPLETED) {
            throw AppException.conflict("订单完成后才能评价，当前状态：" + order.getStatus());
        }
        ShopOrderLine line = orderLines.findById(orderLineId)
                .filter(l -> l.getOrderId().equals(order.getId()))
                .orElseThrow(() -> AppException.notFound("订单行不存在"));
        var sku = skus.findById(line.getSkuId())
                .orElseThrow(() -> AppException.notFound("商品规格不存在"));

        ShopReview review = ShopReview.of(line.getId(), sku.getId(), sku.getProductId(), userId,
                rating, content, imageKeys);

        // 🔴 同步过滤：先出结论再落库，避免出现「短暂可见」的窗口
        applyModerationResult(review, content, imageKeys);

        try {
            return reviews.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            // 🔴 每个订单行只能评一次（库级唯一约束）。并发双提交在这里被挡下。
            throw AppException.conflict("该商品你已经评价过了");
        }
    }

    /**
     * 重提被拦截的评价。
     *
     * <p>🔴 <b>覆盖原记录而不是新建一条</b>：唯一约束在订单行上，新建会 409；
     * 而「改了再提」在用户眼里就是同一条评价的第二次尝试。
     */
    @Transactional
    public ShopReview resubmit(long userId, long reviewId, int rating, String content,
            List<String> imageKeys) {
        ShopReview existing = reviews.findById(reviewId)
                .filter(r -> r.getUserId() == userId)
                .orElseThrow(() -> AppException.notFound("评价不存在"));
        if (existing.isPublished()) {
            // 已发布的评价不给改 —— 改了别人已经看过的内容属于另一件事（首版不做编辑）
            throw AppException.conflict("已发布的评价不可修改");
        }
        ShopReview replacement = ShopReview.of(existing.getShopOrderLineId(), existing.getSkuId(),
                existing.getProductId(), userId, rating, content, imageKeys);
        applyModerationResult(replacement, content, imageKeys);
        reviews.delete(existing);
        reviews.flush();
        return reviews.saveAndFlush(replacement);
    }

    /**
     * 三方降级后的重试（由既有 {@code @Scheduled} 组件驱动，不新建定时器）。
     *
     * @return 本次收敛掉的条数
     */
    public int retryPending(int limit) {
        int settled = 0;
        for (ShopReview r : reviews.findByReviewStatusOrderByCreatedAtAsc(ReviewStatus.PENDING,
                PageRequest.of(0, Math.max(1, limit)))) {
            try {
                if (retryOne(r.getId())) {
                    settled++;
                }
            } catch (RuntimeException e) {
                log.warn("评价审核重试失败 id={} cause={}", r.getId(),
                        e.getClass().getSimpleName());
            }
        }
        return settled;
    }

    @Transactional
    public boolean retryOne(long reviewId) {
        ShopReview r = reviews.findById(reviewId).orElse(null);
        if (r == null || r.getReviewStatus() != ReviewStatus.PENDING) {
            return false;
        }
        applyModerationResult(r, r.getContent(), r.imageKeyList());
        reviews.save(r);
        return r.getReviewStatus() != ReviewStatus.PENDING;
    }

    // ---------- 详情页读 ----------

    /** 🔴 只出已发布的，按时间倒序（Story 7.3）。 */
    @Transactional(readOnly = true)
    public List<ShopReview> publishedFor(long productId, int limit) {
        return reviews.findByProductIdAndReviewStatusOrderByCreatedAtDescIdDesc(productId,
                ReviewStatus.PUBLISHED, PageRequest.of(0, Math.max(1, limit)));
    }

    @Transactional(readOnly = true)
    public long publishedCount(long productId) {
        return reviews.countByProductIdAndReviewStatus(productId, ReviewStatus.PUBLISHED);
    }

    /** 平均星级。🔴 无评价时返回 null，<b>不是 0</b> —— 0 会被读成「零分」。 */
    @Transactional(readOnly = true)
    public Double averageRating(long productId) {
        return reviews.averageRating(productId);
    }

    @Transactional(readOnly = true)
    public List<ShopReview> myReviews(long userId) {
        return reviews.findByUserIdOrderByCreatedAtDescIdDesc(userId);
    }

    /** 该订单行是否已评过（评价入口据此置灰）。 */
    @Transactional(readOnly = true)
    public boolean alreadyReviewed(long orderLineId) {
        return reviews.findByShopOrderLineId(orderLineId).isPresent();
    }

    // ---------- 内部 ----------

    /**
     * 跑一次同步过滤并把结论写进实体。
     *
     * <p>🔒 <b>不记原文</b>：只记 verdict 分类与评分。原文已经在 {@code content} 列里，
     * 在审核字段里再存一份只是多一处泄露面。
     */
    private void applyModerationResult(ShopReview review, String content, List<String> imageKeys) {
        List<String> urls = new ArrayList<>();
        if (imageKeys != null) {
            for (String key : imageKeys) {
                String url = imageUrls.publicUrl(key);
                if (url != null) {
                    urls.add(url);
                }
            }
        }
        ModerationOutcome outcome;
        try {
            outcome = moderation.evaluate(content, urls);
        } catch (RuntimeException e) {
            // 🔴 fail-closed：拿不到结论一律不发布
            log.warn("评价内容审核不可用，落待重试 cause={}", e.getClass().getSimpleName());
            review.applyModeration(ReviewStatus.PENDING, "UNAVAILABLE", null);
            return;
        }
        ReviewStatus status = statusOf(outcome);
        review.applyModeration(status, outcome.topCategory(), outcome.riskScore());
    }

    /**
     * verdict → 评价状态。
     *
     * <p>🔴 <b>{@code RISKY} 也拦</b>（AC：命中高风险即拦截不发布）——
     * 内容审核在别处会把 RISKY 送人工队列，但评价<b>不进人工队列</b>：
     * 让用户自己改两个字，比让运营每天筛一遍评价快得多，也准得多。
     */
    static ReviewStatus statusOf(ModerationOutcome outcome) {
        if (outcome.degraded()
                || outcome.verdict() == ContentModerationService.Verdict.DEGRADED) {
            return ReviewStatus.PENDING;    // fail-closed，但不怪用户
        }
        return switch (outcome.verdict()) {
            case PASS -> ReviewStatus.PUBLISHED;
            case TEXT_BLOCKED, IMAGE_BLOCKED, RISKY -> ReviewStatus.BLOCKED;
            default -> ReviewStatus.PENDING;
        };
    }
}

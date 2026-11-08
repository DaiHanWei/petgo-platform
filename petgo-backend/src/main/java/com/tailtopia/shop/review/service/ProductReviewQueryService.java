package com.tailtopia.shop.review.service;

import com.tailtopia.auth.dto.ActorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.shared.paging.KeysetCursor;
import com.tailtopia.shop.dto.ShopProductDetailView;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.review.domain.ReviewStatus;
import com.tailtopia.shop.review.domain.ShopReview;
import com.tailtopia.shop.review.dto.ProductReviewsView;
import com.tailtopia.shop.review.dto.ShopReviewView;
import com.tailtopia.shop.review.repository.ShopReviewRepository;
import com.tailtopia.shop.review.repository.ShopReviewStats;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品详情页 / 全部评价页的评价读取（v1.4.2 shop Story 6.1 AC9 ~ AC11，D-17，PRD FR-133.6）。
 *
 * <p>🔴 口径：只读「已发布且未隐藏」；条数与均分同口径（隐藏的不计入）。
 * 评价列表受 {@code shipping_settings.review_display_enabled} 开关控制（<b>默认关</b>）：
 * 关闭时只给条数与均分、条目为空（旧版 App 本来就只读这两个数）。
 *
 * <p>条目补 {@code nickname}（C-9；取社区昵称后经 {@link ReviewerNameMasker} 打码，「首词 + 末词首字母.」；
 * 注销 / 封号 → null）与
 * {@code specName}（订单行下单时的规格快照）——<b>整页各一次批量查</b>，不逐条查。
 */
@Service
public class ProductReviewQueryService {

    /** 详情页内展示条数（PRD：页内 3 条 +「查看全部」）。 */
    public static final int DETAIL_ITEMS = 3;

    /** {@code /reviews} 每页上限（沿用 Story 7.3 的 20）。 */
    public static final int MAX_PAGE_SIZE = 20;

    private final ShopReviewRepository reviews;
    private final ShippingSettingsRepository settings;
    private final ShopOrderLineRepository orderLines;
    private final AccountQueryService accounts;
    private final ShopImageUrlResolver imageUrls;

    public ProductReviewQueryService(ShopReviewRepository reviews, ShippingSettingsRepository settings,
            ShopOrderLineRepository orderLines, AccountQueryService accounts, ShopImageUrlResolver imageUrls) {
        this.reviews = reviews;
        this.settings = settings;
        this.orderLines = orderLines;
        this.accounts = accounts;
        this.imageUrls = imageUrls;
    }

    /** 评价列表展示开关（单行配置缺失按关）。 */
    @Transactional(readOnly = true)
    public boolean isDisplayEnabled() {
        return settings.findById(ShippingSettings.SINGLETON_ID)
                .map(ShippingSettings::isReviewDisplayEnabled).orElse(false);
    }

    /**
     * 商品详情的评价摘要（AC11）：条数 + 均分一条 SQL；开关开时再取最新 {@value #DETAIL_ITEMS} 条。
     */
    @Transactional(readOnly = true)
    public ShopProductDetailView.ReviewSummary summaryFor(long productId) {
        boolean enabled = isDisplayEnabled();
        ShopReviewStats stats = stats(productId);
        List<ShopReviewView> items = List.of();
        if (enabled && stats.total() > 0) {
            items = toPublicViews(reviews.findByProductIdAndReviewStatusAndHiddenAtIsNullOrderByCreatedAtDescIdDesc(
                    productId, ReviewStatus.PUBLISHED, PageRequest.of(0, DETAIL_ITEMS)));
        }
        return new ShopProductDetailView.ReviewSummary(stats.total(), stats.averageRating(), enabled, items);
    }

    /**
     * {@code GET /shop/products/{token}/reviews}（AC10）：开关关 → 只给条数与均分；开 → 游标分页。
     *
     * @param cursor 上一页的 {@code nextCursor}；空 / 坏游标 → 首页（不报错，同 {@link KeysetCursor} 口径）
     * @param limit  每页条数；空或非正取 {@value #MAX_PAGE_SIZE}，上限 {@value #MAX_PAGE_SIZE}
     */
    @Transactional(readOnly = true)
    public ProductReviewsView pageFor(long productId, String cursor, Integer limit) {
        boolean enabled = isDisplayEnabled();
        ShopReviewStats stats = stats(productId);
        if (!enabled || stats.total() == 0) {
            return new ProductReviewsView(stats.total(), stats.averageRating(), List.of(), enabled, null, false);
        }
        int size = limit == null || limit <= 0 ? MAX_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        KeysetCursor from = KeysetCursor.decodeOrNull(cursor);
        if (from == null) {
            from = KeysetCursor.firstPage();
        }
        List<ShopReview> rows = reviews.pageVisible(productId, from.createdAt(), from.id(),
                PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<ShopReview> page = hasMore ? rows.subList(0, size) : rows;
        String next = null;
        if (hasMore) {
            ShopReview last = page.get(page.size() - 1);
            next = new KeysetCursor(last.getCreatedAt(), last.getId()).encode();
        }
        return new ProductReviewsView(stats.total(), stats.averageRating(), toPublicViews(page), true, next,
                hasMore);
    }

    private ShopReviewStats stats(long productId) {
        ShopReviewStats s = reviews.visibleStats(productId);
        return s == null ? ShopReviewStats.empty() : s;
    }

    /** 公开条目视图：昵称、规格名各一次批量查（不 N+1）。 */
    List<ShopReviewView> toPublicViews(List<ShopReview> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ActorView> actors = accounts.findActorViews(
                rows.stream().map(ShopReview::getUserId).distinct().toList());
        Map<Long, String> specByLine = orderLines.findAllById(
                rows.stream().map(ShopReview::getShopOrderLineId).distinct().toList()).stream()
                .collect(Collectors.toMap(ShopOrderLine::getId, ShopOrderLine::getSpecName, (a, b) -> a));
        List<ShopReviewView> out = new ArrayList<>(rows.size());
        Function<Long, String> nicknameOf = uid -> {
            ActorView a = actors.get(uid);
            // 🔒 只下发打码值（复审修复）：社区昵称可能是 Google 真名
            return a == null || !a.active() ? null : ReviewerNameMasker.mask(a.nickname());
        };
        for (ShopReview r : rows) {
            out.add(ShopReviewView.published(r, imageUrls.publicUrls(r.imageKeyList()),
                    nicknameOf.apply(r.getUserId()), specByLine.get(r.getShopOrderLineId())));
        }
        return out;
    }
}

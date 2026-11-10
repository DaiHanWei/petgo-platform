package com.tailtopia.shop.service;

import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.domain.BannerTargetType;
import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.repository.ShopBannerRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Toko 首页 banner 选取的<b>单一来源</b>（v1.4.2 shop Story 6.1 AC4，D-16，PRD FR-132）。
 *
 * <p>🔴 {@code GET /shop/banners}、旧 {@code GET /shop/banner}、后台「生效中」标记<b>三处都只调
 * {@link #liveBanners}</b>：取用规则（排期、目标有效、上限）只在这里写一遍，三处不会静默分叉。
 *
 * <p>规则：已上架 + 在排期内的候选（最多 {@value #CANDIDATE_LIMIT} 行）→ 目标无效的<b>整张剔除</b>
 * （商品不在售 / 券此刻不可领 / 图片 URL 拼不出）→ 按原顺序取前 {@value #MAX_BANNERS} 张。
 * 目标有效性<b>批量查</b>（商品一次、券一次），不逐张查库。
 */
@Service
public class ShopBannerQueryService {

    /** 轮播上限（PRD FR-132）。 */
    public static final int MAX_BANNERS = 6;

    /** 候选上限：剔除后仍够 6 张的余量，同时给查询一个硬上限。 */
    static final int CANDIDATE_LIMIT = 30;

    /** 新端点对 CATEGORY 的下发值（品类筛选下线，跳 Toko 首页）。不落库、不进枚举。 */
    public static final String WIRE_TOKO_HOME = "TOKO_HOME";

    private final ShopBannerRepository banners;
    private final ShopProductRepository products;
    private final ShopCouponRepository coupons;
    private final ShopImageUrlResolver imageUrls;

    public ShopBannerQueryService(ShopBannerRepository banners, ShopProductRepository products,
            ShopCouponRepository coupons, ShopImageUrlResolver imageUrls) {
        this.banners = banners;
        this.products = products;
        this.coupons = coupons;
        this.imageUrls = imageUrls;
    }

    /**
     * 一张生效中的 banner 与它的下发形态。
     *
     * @param wireTargetType  新端点下发的跳转类型（NONE / PRODUCT / TOKO_HOME / EXTERNAL / COUPON）
     * @param wireTargetValue 新端点下发的跳转值（NONE / TOKO_HOME 时为 null）
     */
    public record LiveBanner(ShopBanner banner, String imageUrl, String wireTargetType,
            String wireTargetValue) {
    }

    /** 此刻生效的 banner（最多 6 张，按取用顺序）。 */
    @Transactional(readOnly = true)
    public List<LiveBanner> liveBanners(Instant now) {
        List<ShopBanner> cands = banners.findLiveCandidates(now, PageRequest.of(0, CANDIDATE_LIMIT));
        if (cands.isEmpty()) {
            return List.of();
        }
        Set<String> productTokens = tokensOf(cands, BannerTargetType.PRODUCT);
        Set<String> couponTokens = tokensOf(cands, BannerTargetType.COUPON);
        // tokensOf 为空时不发查询（IN () 也不合法）
        Set<String> onSale = productTokens.isEmpty() ? Set.of() : products.findActivePublicTokens(productTokens);
        Set<String> claimable = couponTokens.isEmpty() ? Set.of()
                : coupons.findClaimableTokens(couponTokens, now);
        return select(cands, onSale, claimable, imageUrls::publicUrl, MAX_BANNERS);
    }

    /**
     * 纯函数部分（L0 单测入口）：给定候选（已按取用顺序、已过滤 active 与排期）、在售商品 token 集合、
     * 可领券 token 集合与 key→URL 函数，算出最终下发的 banner。
     */
    public static List<LiveBanner> select(List<ShopBanner> candidates, Set<String> onSaleProducts,
            Set<String> claimableCoupons, Function<String, String> urlOf, int limit) {
        List<LiveBanner> out = new ArrayList<>();
        for (ShopBanner b : candidates) {
            if (out.size() >= limit) {
                break;
            }
            LiveBanner live = toLive(b, onSaleProducts, claimableCoupons, urlOf);
            if (live != null) {
                out.add(live);
            }
        }
        return out;
    }

    /**
     * 单张已上架、在排期内的 banner 的下发形态（{@link #select} 与后台六态 {@link #states} <b>共用这一处判定</b>，
     * V1.4.2 shop-admin Story 3.1 · AD-7）。{@code null} = App 不展示：目标失效（商品不在售 / 券此刻不可领）或图片 URL 拼不出。
     */
    static LiveBanner toLive(ShopBanner b, Set<String> onSaleProducts, Set<String> claimableCoupons,
            Function<String, String> urlOf) {
        String url = urlOf.apply(b.getImageKey());
        if (url == null || url.isBlank()) {
            return null;   // CDN base 未配置：裂图不如不展示（与旧端点 204 同口径）
        }
        BannerTargetType type = b.getTargetType() == null ? BannerTargetType.NONE : b.getTargetType();
        String value = b.getTargetValue();
        return switch (type) {
            case PRODUCT -> value != null && onSaleProducts.contains(value)
                    ? new LiveBanner(b, url, type.name(), value) : null;
            case COUPON -> value != null && claimableCoupons.contains(value)
                    ? new LiveBanner(b, url, type.name(), value) : null;
            case CATEGORY -> new LiveBanner(b, url, WIRE_TOKO_HOME, null);
            case EXTERNAL -> new LiveBanner(b, url, type.name(), value);
            case NONE -> new LiveBanner(b, url, BannerTargetType.NONE.name(), null);
        };
    }

    /** 后台列表六态（V1.4.2 shop-admin Story 3.1 · AD-7）。 */
    public enum BannerState {
        /** 已上架、上线时间未到。 */
        PENDING,
        /** 此刻在 App 轮播里。 */
        LIVE,
        /** 已上架、在排期内，但跳转目标失效（或图片 URL 拼不出），App 自动不展示。 */
        TARGET_INVALID,
        /** 已上架、在排期内、目标有效，但排在第 {@value #MAX_BANNERS} 张之后。 */
        OVER_LIMIT,
        /** 已上架、下线时间已过。 */
        EXPIRED,
        /** 未上架。 */
        INACTIVE
    }

    /**
     * 六态纯函数（L0 单测入口）：{@code allInOrder} 为<b>全部</b> banner、按取用顺序。与 {@link #select} 同一套生效判定
     * （{@link #toLive}）、同一个上限：前 {@code limit} 张有效的为 LIVE，之后的有效张为 OVER_LIMIT。
     */
    public static Map<Long, BannerState> states(List<ShopBanner> allInOrder, Instant now, Set<String> onSaleProducts,
            Set<String> claimableCoupons, Function<String, String> urlOf, int limit) {
        return states(allInOrder, now, onSaleProducts, claimableCoupons, urlOf, limit, Integer.MAX_VALUE);
    }

    /**
     * 同上，并模拟 App 的候选上限 {@code candidateLimit}（{@link #liveBanners} 只看前 {@value #CANDIDATE_LIMIT} 张已上架且在排期内的）：
     * 排在候选上限之后的有效张 App 根本看不到，标 OVER_LIMIT —— 后台与 App 不会因候选截断而分叉。
     */
    public static Map<Long, BannerState> states(List<ShopBanner> allInOrder, Instant now, Set<String> onSaleProducts,
            Set<String> claimableCoupons, Function<String, String> urlOf, int limit, int candidateLimit) {
        Map<Long, BannerState> out = new LinkedHashMap<>();
        int live = 0;
        int candidates = 0;
        for (ShopBanner b : allInOrder) {
            BannerState s;
            if (!b.isActive()) {
                s = BannerState.INACTIVE;
            } else if (b.getStartAt() != null && b.getStartAt().isAfter(now)) {
                s = BannerState.PENDING;
            } else if (b.getEndAt() != null && !b.getEndAt().isAfter(now)) {
                s = BannerState.EXPIRED;
            } else if (candidates++ >= candidateLimit) {
                s = BannerState.OVER_LIMIT;
            } else if (toLive(b, onSaleProducts, claimableCoupons, urlOf) == null) {
                s = BannerState.TARGET_INVALID;
            } else if (live < limit) {
                s = BannerState.LIVE;
                live++;
            } else {
                s = BannerState.OVER_LIMIT;
            }
            out.put(b.getId(), s);
        }
        return out;
    }

    /** 后台用：对全部 banner（取用顺序）算六态；商品 / 券有效性批量查（同 {@link #liveBanners}）。 */
    @Transactional(readOnly = true)
    public Map<Long, BannerState> statesOf(List<ShopBanner> allInOrder, Instant now) {
        Set<String> productTokens = tokensOf(allInOrder, BannerTargetType.PRODUCT);
        Set<String> couponTokens = tokensOf(allInOrder, BannerTargetType.COUPON);
        Set<String> onSale = productTokens.isEmpty() ? Set.of() : products.findActivePublicTokens(productTokens);
        Set<String> claimable = couponTokens.isEmpty() ? Set.of() : coupons.findClaimableTokens(couponTokens, now);
        return states(allInOrder, now, onSale, claimable, imageUrls::publicUrl, MAX_BANNERS, CANDIDATE_LIMIT);
    }

    static Set<String> tokensOf(Collection<ShopBanner> cands, BannerTargetType type) {
        Set<String> out = new LinkedHashSet<>();
        for (ShopBanner b : cands) {
            if (b.getTargetType() == type && b.getTargetValue() != null) {
                out.add(b.getTargetValue());
            }
        }
        return out;
    }
}

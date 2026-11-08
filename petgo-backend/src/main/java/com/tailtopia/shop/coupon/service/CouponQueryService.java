package com.tailtopia.shop.coupon.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.paging.KeysetCursor;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.dto.CouponPage;
import com.tailtopia.shop.coupon.dto.CouponView;
import com.tailtopia.shop.coupon.dto.CouponViews;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 券查询（v1.4.2 shop Story 2.2 AC6 ~ AC8，D-6）：首页「给你的券」、商品可领券、我的券（两页签 + 计数）。只读。
 *
 * <p>时间比较一律用调用方传入的服务端 {@code now}（P-6）。对外只有 public_token（AC11）。
 */
@Service
public class CouponQueryService {

    /** 首页「给你的券」总数上限。 */
    static final int FOR_YOU_LIMIT = 20;

    /** 我的券每页条数。 */
    static final int PAGE_SIZE = 20;

    public enum Tab {
        AVAILABLE, USED;

        /** 空 → AVAILABLE；大小写不敏感；非法 → 422。 */
        public static Tab parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AVAILABLE;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw AppException.validation("券页签非法");
            }
        }
    }

    private final ShopCouponRepository coupons;
    private final UserCouponRepository userCoupons;
    private final ShopProductRepository products;
    private final ShopOrderRepository orders;

    public CouponQueryService(ShopCouponRepository coupons, UserCouponRepository userCoupons,
            ShopProductRepository products, ShopOrderRepository orders) {
        this.coupons = coupons;
        this.userCoupons = userCoupons;
        this.products = products;
        this.orders = orders;
    }

    /**
     * 首页「给你的券」（AC6）：① 本人 NEWBIE 来源、AVAILABLE 且未过期的用户券置顶；② 发放中 CLAIM 模板——
     * 未达限领且未领完 → {@code claimed=false}，已达限领 → {@code claimed=true}，未领且已领完 → 不出现；
     * 第 ② 组未领在前、已领在后，同组按 {@code issue_start_at} 倒序；总数上限 20。CODE 类不出现。
     */
    @Transactional(readOnly = true)
    public List<CouponView> forYou(long userId, Instant now) {
        List<CouponView> out = new ArrayList<>();
        List<UserCoupon> newbie = userCoupons.findUsableBySource(userId, UserCouponSource.NEWBIE, now);
        if (!newbie.isEmpty()) {
            Map<Long, ShopCoupon> templates = templatesOf(newbie.stream().map(UserCoupon::getCouponId).toList());
            for (UserCoupon uc : newbie) {
                ShopCoupon c = templates.get(uc.getCouponId());
                if (c != null) {
                    out.add(CouponViews.owned(uc, c, now));
                }
            }
        }
        List<ShopCoupon> claimable = coupons.findIssuable(CouponAcquireType.CLAIM, now);
        Map<Long, Long> held = heldCounts(userId, claimable);
        List<CouponView> unclaimed = new ArrayList<>();
        List<CouponView> claimed = new ArrayList<>();
        for (ShopCoupon c : claimable) {   // 仓储已按 issue_start_at 倒序
            Boolean state = claimState(c, held.getOrDefault(c.getId(), 0L));
            if (Boolean.TRUE.equals(state)) {
                claimed.add(CouponViews.claimable(c, true, now));
            } else if (state != null) {
                unclaimed.add(CouponViews.claimable(c, false, now));
            }
        }
        out.addAll(unclaimed);
        out.addAll(claimed);
        return out.size() > FOR_YOU_LIMIT ? List.copyOf(out.subList(0, FOR_YOU_LIMIT)) : out;
    }

    /**
     * 商品可领券（AC7）：发放中 CLAIM 模板里覆盖该商品的；未领完或本人领过的才出现。
     * 商品不存在 / 已下架 → 空（不泄露上下架状态）。{@code viewerUserId} 为 null（游客）时 {@code claimed} 恒 false。
     */
    @Transactional(readOnly = true)
    public List<CouponView> forProduct(String productToken, Long viewerUserId, Instant now) {
        if (productToken == null || productToken.isBlank()) {
            return List.of();
        }
        ShopProduct p = products.findByPublicTokenAndActiveTrue(productToken).orElse(null);
        if (p == null) {
            return List.of();
        }
        List<ShopCoupon> covering = coupons.findIssuable(CouponAcquireType.CLAIM, now).stream()
                .filter(c -> CouponScopeMatcher.covers(c, p.getPublicToken(), p.getCategory()))
                .toList();
        if (covering.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> held = viewerUserId == null ? Map.of() : heldCounts(viewerUserId, covering);
        List<CouponView> out = new ArrayList<>();
        for (ShopCoupon c : covering) {
            Boolean state = claimState(c, held.getOrDefault(c.getId(), 0L));
            if (state != null) {
                out.add(CouponViews.claimable(c, state, now));
            }
        }
        return out;
    }

    /**
     * 我的券（AC8 / C-3 / C-9）：可用页签 = AVAILABLE 且未过期 + LOCKED（{@code lockedByPendingOrder=true}），
     * 按 {@code claimed_at DESC, id DESC}；已使用页签 = USED，按 {@code updated_at DESC, id DESC}，带使用订单号 / token。
     * 坏游标按首页。
     */
    @Transactional(readOnly = true)
    public CouponPage myCoupons(long userId, Tab tab, String cursor, Instant now) {
        KeysetCursor from = KeysetCursor.decodeOrNull(cursor);
        if (from == null) {
            from = KeysetCursor.firstPage();
        }
        PageRequest probe = PageRequest.of(0, PAGE_SIZE + 1);
        List<UserCoupon> rows = tab == Tab.USED
                ? userCoupons.pageUsed(userId, from.createdAt(), from.id(), probe)
                : userCoupons.pageAvailable(userId, now, from.createdAt(), from.id(), probe);
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<UserCoupon> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;

        Map<Long, ShopCoupon> templates = page.isEmpty() ? Map.of()
                : templatesOf(page.stream().map(UserCoupon::getCouponId).toList());
        Map<Long, ShopOrder> usedOrders = tab == Tab.USED ? usedOrdersOf(page) : Map.of();
        List<CouponView> items = new ArrayList<>(page.size());
        for (UserCoupon uc : page) {
            ShopCoupon c = templates.get(uc.getCouponId());
            if (c == null) {
                continue;
            }
            ShopOrder o = uc.getUsedOrderId() == null ? null : usedOrders.get(uc.getUsedOrderId());
            items.add(CouponViews.owned(uc, c, now, false,
                    o == null ? null : o.getDisplayNo(), o == null ? null : o.getPublicToken()));
        }
        String next = null;
        if (hasMore) {
            UserCoupon last = page.get(page.size() - 1);
            next = new KeysetCursor(tab == Tab.USED ? last.getUpdatedAt() : last.getClaimedAt(), last.getId())
                    .encode();
        }
        return new CouponPage(items, next, userCoupons.countUsable(userId, now));
    }

    /** 可用券张数（AC8.4）：AVAILABLE 且未过期，不含 LOCKED。 */
    @Transactional(readOnly = true)
    public long availableCount(long userId, Instant now) {
        return userCoupons.countUsable(userId, now);
    }

    /** 用户券 + 其模板（Story 3.1 结算候选用）。 */
    public record OwnedCoupon(UserCoupon userCoupon, ShopCoupon coupon) {
    }

    /**
     * 结算候选券（Story 3.1 AC1.6）：本人 AVAILABLE 且在有效期内的用户券连同模板，先到期在前。
     * 🔴 只读（P-2）：不占券、不改状态。
     */
    @Transactional(readOnly = true)
    public List<OwnedCoupon> checkoutCandidates(long userId, Instant now) {
        List<UserCoupon> rows = userCoupons.findUsable(userId, now);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ShopCoupon> templates = templatesOf(rows.stream().map(UserCoupon::getCouponId).toList());
        List<OwnedCoupon> out = new ArrayList<>(rows.size());
        for (UserCoupon uc : rows) {
            ShopCoupon c = templates.get(uc.getCouponId());
            if (c != null) {
                out.add(new OwnedCoupon(uc, c));
            }
        }
        return out;
    }

    /** 本人名下某张用户券（任意状态）连同模板；不是本人 / 不存在 → empty（Story 3.1 手选券展示用）。 */
    @Transactional(readOnly = true)
    public java.util.Optional<OwnedCoupon> owned(long userId, String userCouponToken) {
        if (userCouponToken == null || userCouponToken.isBlank()) {
            return java.util.Optional.empty();
        }
        return userCoupons.findByPublicTokenAndUserId(userCouponToken, userId)
                .flatMap(uc -> coupons.findById(uc.getCouponId()).map(c -> new OwnedCoupon(uc, c)));
    }

    /**
     * 模板对本人的领取状态（复审修复）：{@code true} = 显示「Diklaim」（已达每人限领，或已领完而本人领过）；
     * {@code false} = 显示「Klaim」（未达限领且未领完）；{@code null} = 不出现（本人未领且已领完）。
     * 已领完时不能再给「Klaim」：点了必然 sold-out。
     */
    static Boolean claimState(ShopCoupon c, long heldCount) {
        if (heldCount >= c.getPerUserLimit()) {
            return true;
        }
        if (NewbieCouponService.isSoldOut(c)) {
            return heldCount > 0 ? Boolean.TRUE : null;
        }
        return false;
    }

    private Map<Long, ShopCoupon> templatesOf(Collection<Long> couponIds) {
        return coupons.findAllById(couponIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(ShopCoupon::getId, Function.identity()));
    }

    private Map<Long, ShopOrder> usedOrdersOf(List<UserCoupon> page) {
        List<Long> ids = page.stream().map(UserCoupon::getUsedOrderId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return orders.findAllById(ids).stream()
                .collect(Collectors.toMap(ShopOrder::getId, Function.identity()));
    }

    private Map<Long, Long> heldCounts(long userId, List<ShopCoupon> templates) {
        if (templates.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> out = new HashMap<>();
        for (Object[] row : userCoupons.countByUserGroupedByCoupon(userId,
                templates.stream().map(ShopCoupon::getId).toList())) {
            out.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return out;
    }
}

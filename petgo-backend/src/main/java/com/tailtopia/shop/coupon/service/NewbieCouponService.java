package com.tailtopia.shop.coupon.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新人券（v1.4.2 shop Story 2.2 AC4 / AC5，D-5 / FR-130.5）。
 *
 * <p>App 每次进入 Toko 调 {@link #onEntry}（幂等）：当前有新人券 → 有效真实普通用户 → 注册未满 30 天
 * （严格 {@code created_at > now − 30d}，拍板 ④）→ 从未有已支付电商订单 → 锁模板行 → 本人没领过 → 扣总量成功
 * → 经账本发券（{@code source=NEWBIE}）。任何一步不满足都静默返回 false，不报错。
 * 「活动开始前进过 Toko」天然补发：每次进入都判。
 */
@Service
public class NewbieCouponService {

    private static final Logger log = LoggerFactory.getLogger(NewbieCouponService.class);

    /** 新人窗口：注册后 30 天（严格小于）。 */
    static final Duration NEWBIE_WINDOW = Duration.ofDays(30);

    private final ShopCouponRepository coupons;
    private final UserCouponRepository userCoupons;
    private final CouponLedgerService ledger;
    private final AccountQueryService accounts;
    private final ShopOrderRepository orders;
    private final CouponRowLock rowLock;

    public NewbieCouponService(ShopCouponRepository coupons, UserCouponRepository userCoupons,
            CouponLedgerService ledger, AccountQueryService accounts, ShopOrderRepository orders,
            CouponRowLock rowLock) {
        this.rowLock = rowLock;
        this.coupons = coupons;
        this.userCoupons = userCoupons;
        this.ledger = ledger;
        this.accounts = accounts;
        this.orders = orders;
    }

    /**
     * 当前新人券（AC4.2.1）：ACTIVE 且在发放期内；2.1 保证同一时刻最多一张，查到多张取 {@code issue_start_at} 最晚的并 WARN。
     */
    @Transactional(readOnly = true)
    public Optional<ShopCoupon> currentCampaign(Instant now) {
        List<ShopCoupon> rows = coupons.findIssuable(CouponAcquireType.NEWBIE, now);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() > 1) {
            log.warn("同一时刻存在多张新人券 count={}，取发放开始最晚的 couponId={}", rows.size(), rows.get(0).getId());
        }
        return Optional.of(rows.get(0));
    }

    /** 游客活动接口（AC5）：有当前新人券且未领完才返回。 */
    @Transactional(readOnly = true)
    public Optional<ShopCoupon> guestCampaign(Instant now) {
        return currentCampaign(now).filter(c -> !isSoldOut(c));
    }

    @Transactional
    public boolean onEntry(long userId, Instant now) {
        ShopCoupon current = currentCampaign(now).orElse(null);
        if (current == null) {
            return false;
        }
        if (!accounts.isActiveRegularUser(userId)) {
            return false;
        }
        Instant registeredAt = accounts.createdAtOf(userId).orElse(null);
        if (registeredAt == null || !registeredAt.isAfter(now.minus(NEWBIE_WINDOW))) {
            return false;
        }
        if (orders.existsByUserIdAndPaidAtIsNotNull(userId)) {
            return false;
        }
        ShopCoupon c = rowLock.lockFresh(current.getId()).orElse(null);
        if (c == null || !c.isIssuableAt(now)) {
            return false;
        }
        if (userCoupons.existsByUserIdAndCouponId(userId, c.getId())) {
            return false;   // 幂等：重复进入不重复发
        }
        if (coupons.incrementIssuedIfAvailable(c.getId()) == 0) {
            log.info("新人券已领完 userId={} couponId={}", userId, c.getId());
            return false;   // 领完静默不发（待确认 ④）
        }
        ledger.issue(userId, c, UserCouponSource.NEWBIE, now);
        log.info("新人券发放 userId={} couponId={}", userId, c.getId());
        return true;
    }

    static boolean isSoldOut(ShopCoupon c) {
        return c.getTotalQuantity() != null && c.getIssuedCount() >= c.getTotalQuantity();
    }
}

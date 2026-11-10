package com.tailtopia.shop.coupon.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.dto.CouponView;
import com.tailtopia.shop.coupon.dto.CouponViews;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主动领券（v1.4.2 shop Story 2.2 AC2，D-6 / FR-130.4）——首页、详情抽屉、banner 三个入口共用。
 *
 * <p>判序（拍板 ①）：不存在 / 非 CLAIM 类 → 404；锁模板行；停用或不在发放期 → 409 not-in-period；
 * 已达每人限领（任何状态都算）→ 409 already-claimed；条件扣总量 0 行 → 409 sold-out；最后经账本发券。
 * 🔴 发券只经 {@link CouponLedgerService#issue}（P-2）。日志只记 userId / 模板内部 id / 结果分类。
 */
@Service
public class CouponClaimService {

    private static final Logger log = LoggerFactory.getLogger(CouponClaimService.class);

    private final ShopCouponRepository coupons;
    private final UserCouponRepository userCoupons;
    private final CouponLedgerService ledger;
    private final CouponRowLock rowLock;

    public CouponClaimService(ShopCouponRepository coupons, UserCouponRepository userCoupons,
            CouponLedgerService ledger, CouponRowLock rowLock) {
        this.coupons = coupons;
        this.userCoupons = userCoupons;
        this.ledger = ledger;
        this.rowLock = rowLock;
    }

    @Transactional
    public CouponView claim(long userId, String couponToken, CouponClaimSource source, Instant now) {
        ShopCoupon found = couponToken == null || couponToken.isBlank() ? null
                : coupons.findByPublicToken(couponToken).orElse(null);
        if (found == null || found.getAcquireType() != CouponAcquireType.CLAIM) {
            // 新人券、兑换码券不能直接领：与不存在同一错误，不泄露模板类型
            throw AppException.couponNotFound("券不存在");
        }
        ShopCoupon c = rowLock.lockFresh(found.getId())
                .orElseThrow(() -> AppException.couponNotFound("券不存在"));
        if (!c.isIssuableAt(now)) {
            log.info("领券失败 userId={} couponId={} result=NOT_IN_PERIOD", userId, c.getId());
            throw AppException.couponNotInPeriod("券不在发放期或已停用");
        }
        if (userCoupons.countByUserIdAndCouponId(userId, c.getId()) >= c.getPerUserLimit()) {
            log.info("领券失败 userId={} couponId={} result=ALREADY_CLAIMED", userId, c.getId());
            throw AppException.couponAlreadyClaimed("已达每人限领");
        }
        if (coupons.incrementIssuedIfAvailable(c.getId()) == 0) {
            log.info("领券失败 userId={} couponId={} result=SOLD_OUT", userId, c.getId());
            throw AppException.couponSoldOut("券已领完");
        }
        UserCoupon uc = ledger.issue(userId, c, source.ledgerSource(), now);
        log.info("领券成功 userId={} couponId={} source={}", userId, c.getId(), source);
        return CouponViews.owned(uc, c, now);
    }
}

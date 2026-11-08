package com.tailtopia.shop.coupon.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.ShopCouponCode;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.dto.CouponView;
import com.tailtopia.shop.coupon.dto.CouponViews;
import com.tailtopia.shop.coupon.repository.ShopCouponCodeRepository;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兑换码兑换（v1.4.2 shop Story 2.2 AC3，D-4 / FR-130.9）。
 *
 * <p><b>防暴力试码</b>：只对「不存在 / 已被使用」两类计失败；同账号 1 小时内第 10 次失败照常返回该次错误，
 * 同时写锁定键，之后 1 小时内<b>任何</b>兑换（含正确的码）一律 429（拍板 ②）。
 * 失败计数在抛业务异常<b>之前</b>写——Redis 不随事务回滚，正是想要的。
 *
 * <p>Redis 直接用 {@link StringRedisTemplate}（{@code RedisRateLimiter.check} 是「每次调用都计数」语义，不适合）；
 * 🔴 <b>Redis 异常一律放行、WARN</b>（fail-open，拍板 ③：防试码是防护，不是业务正确性）。
 *
 * <p>🔴 码明文不进日志：日志只记 userId、模板内部 id 与结果分类。发券只经 {@link CouponLedgerService#issue}。
 */
@Service
public class CouponRedeemService {

    private static final Logger log = LoggerFactory.getLogger(CouponRedeemService.class);

    static final String FAIL_KEY_PREFIX = "rl:coupon:redeem-fail:";
    static final String LOCK_KEY_PREFIX = "rl:coupon:redeem-lock:";
    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofHours(1);

    private final ShopCouponCodeRepository codes;
    private final ShopCouponRepository coupons;
    private final UserCouponRepository userCoupons;
    private final CouponLedgerService ledger;
    private final StringRedisTemplate redis;
    private final CouponRowLock rowLock;

    public CouponRedeemService(ShopCouponCodeRepository codes, ShopCouponRepository coupons,
            UserCouponRepository userCoupons, CouponLedgerService ledger, StringRedisTemplate redis,
            CouponRowLock rowLock) {
        this.rowLock = rowLock;
        this.codes = codes;
        this.coupons = coupons;
        this.userCoupons = userCoupons;
        this.ledger = ledger;
        this.redis = redis;
    }

    @Transactional
    public CouponView redeem(long userId, String rawCode, Instant now) {
        if (isLocked(userId)) {
            log.info("兑换拒绝 userId={} result=LOCKED", userId);
            throw AppException.couponRedeemLocked("兑换尝试次数过多，请稍后再试");
        }
        String code = normalizeOrNull(rawCode);
        ShopCouponCode row = code == null ? null : codes.findByCode(code).orElse(null);
        if (row == null) {
            recordFailure(userId);
            log.info("兑换失败 userId={} result=NOT_FOUND", userId);
            throw AppException.couponNotFound("兑换码不存在");
        }
        ShopCoupon c = rowLock.lockFresh(row.getCouponId())
                .orElseThrow(() -> AppException.couponNotFound("兑换码不存在"));
        if (row.isSingleUse() && row.getRedeemedAt() != null) {
            recordFailure(userId);
            log.info("兑换失败 userId={} couponId={} result=CODE_USED", userId, c.getId());
            throw AppException.couponCodeUsed("兑换码已被使用");
        }
        if (!c.isIssuableAt(now)) {
            log.info("兑换失败 userId={} couponId={} result=NOT_IN_PERIOD", userId, c.getId());
            throw AppException.couponNotInPeriod("券不在发放期或已停用");
        }
        if (userCoupons.countByUserIdAndCouponId(userId, c.getId()) >= c.getPerUserLimit()) {
            log.info("兑换失败 userId={} couponId={} result=ALREADY_CLAIMED", userId, c.getId());
            throw AppException.couponAlreadyClaimed("已达每人限领");
        }
        if (coupons.incrementIssuedIfAvailable(c.getId()) == 0) {
            log.info("兑换失败 userId={} couponId={} result=SOLD_OUT", userId, c.getId());
            throw AppException.couponSoldOut("券已领完");
        }
        if (row.isSingleUse() && codes.redeemSingleUse(row.getId(), userId, now) == 0) {
            // 被并发抢先核销：本事务回滚（含刚扣的总量）
            recordFailure(userId);
            log.info("兑换失败 userId={} couponId={} result=CODE_USED_RACE", userId, c.getId());
            throw AppException.couponCodeUsed("兑换码已被使用");
        }
        UserCoupon uc = ledger.issue(userId, c, UserCouponSource.CODE, now);
        log.info("兑换成功 userId={} couponId={}", userId, c.getId());
        return CouponViews.owned(uc, c, now, true, null, null);
    }

    /** 规范化同 {@link CouponAdminService#normalizeCode}；为空或格式不符 → null（按「不存在」处理并计失败）。 */
    static String normalizeOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CouponAdminService.normalizeCode(raw);
        } catch (AppException e) {
            return null;
        }
    }

    /**
     * 兑换锁定剩余秒数（V1.4.2 shop-admin Story 2.3 · A13，后台用户详情只读展示；客服不能解锁）。
     * 未锁定 / 无 TTL / Redis 读失败 → 空（后台不显示横幅、不报错）。
     */
    public java.util.Optional<Long> redeemLockRemainingSeconds(long userId) {
        try {
            Long ttl = redis.getExpire(LOCK_KEY_PREFIX + userId);
            return ttl != null && ttl > 0 ? java.util.Optional.of(ttl) : java.util.Optional.empty();
        } catch (RuntimeException e) {
            log.warn("兑换锁定 TTL 读取失败 cause={}", e.getClass().getSimpleName());
            return java.util.Optional.empty();
        }
    }

    private boolean isLocked(long userId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(LOCK_KEY_PREFIX + userId));
        } catch (RuntimeException e) {
            log.warn("兑换锁定检查失败（放行）userId={} cause={}", userId, e.getClass().getSimpleName());
            return false;
        }
    }

    private void recordFailure(long userId) {
        String failKey = FAIL_KEY_PREFIX + userId;
        try {
            Long n = redis.opsForValue().increment(failKey);
            // INCR 与 EXPIRE 非原子（复审修复）：首次失败后若 EXPIRE 没写成（进程中断 / Redis 抖动），
            // 计数键会永不过期 → 之后每次失败都补查一次 TTL，-1（无过期）就补设。
            if (n != null && (n == 1L || Long.valueOf(-1L).equals(redis.getExpire(failKey)))) {
                redis.expire(failKey, WINDOW);
            }
            if (n != null && n >= MAX_FAILURES) {
                redis.opsForValue().set(LOCK_KEY_PREFIX + userId, "1", WINDOW);
                redis.delete(failKey);
                log.info("兑换锁定 userId={} windowHours={}", userId, WINDOW.toHours());
            }
        } catch (RuntimeException e) {
            log.warn("兑换失败计数写入失败（放行）userId={} cause={}", userId, e.getClass().getSimpleName());
        }
    }
}

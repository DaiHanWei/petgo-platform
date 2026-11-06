package com.tailtopia.shop.coupon.service;

import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.shop.coupon.domain.CouponReleaseMode;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.repository.UserCouponLedgerDao;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户券账本 —— <b>用户券状态的唯一入口</b>（v1.4.2 shop Story 2.1 AC4，D-2 / D-3 / P-2）。
 *
 * <p>状态机：AVAILABLE →（提交订单 {@link #lock}）LOCKED →（付款 {@link #markUsed}）USED；
 * 取消按 {@link CouponReleaseMode} 退回（{@link #releaseOnCancel}）；运营停用作废 / 注销作废 → VOIDED。
 * 「已过期」不落库（D-2），由 {@code valid_until} 现算。
 *
 * <p>🔴 所有写都经 {@link UserCouponLedgerDao} 的<b>条件语句</b>完成，并发安全靠 {@code WHERE status = …}，
 * 不靠读后写。后续 story 的新写法（2.3 {@code markExpiryNotified}、4.2 迟到款重下单重新锁券）<b>必须加在本类</b>。
 *
 * <p>日志只记内部 id 与计数，不记兑换码、券名。
 */
@Service
public class CouponLedgerService {

    private static final Logger log = LoggerFactory.getLogger(CouponLedgerService.class);

    /** RELATIVE 券到期时刻：领取当天（WIB）+ N 天的 23:59:59 WIB（UI 稿 G1「Berakhir …, 23:59」）。 */
    static final LocalTime RELATIVE_END_OF_DAY = LocalTime.of(23, 59, 59);

    private final UserCouponLedgerDao dao;
    private final UserCouponRepository userCoupons;
    private final ShopTokenGenerator tokens;

    public CouponLedgerService(UserCouponLedgerDao dao, UserCouponRepository userCoupons,
            ShopTokenGenerator tokens) {
        this.dao = dao;
        this.userCoupons = userCoupons;
        this.tokens = tokens;
    }

    /**
     * 发券（2.2 领取 / 兑换 / 新人发放调用）。🔴 {@code MANDATORY}：调用方必须已在事务里锁住模板行
     * （{@code findLockedById}）、校验过发放期 / 每人限领并扣过总量（{@code incrementIssuedIfAvailable}）。
     * 有效期在此按模板物化（{@link #materializeValidity}）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UserCoupon issue(long userId, ShopCoupon coupon, UserCouponSource source, Instant now) {
        Instant[] validity = materializeValidity(coupon, now);
        long id = dao.insert(tokens.generate(), userId, coupon.getId(), source.name(),
                validity[0], validity[1], now);
        log.info("发券 userCouponId={} couponId={} source={}", id, coupon.getId(), source);
        return userCoupons.findById(id)
                .orElseThrow(() -> new IllegalStateException("刚发放的用户券读不回 id=" + id));
    }

    /**
     * 物化有效期：FIXED → 模板起止；RELATIVE → {@code [now, (now 的 WIB 日期 + validDays) 23:59:59 WIB)}。
     *
     * @return {@code [validFrom, validUntil]}
     */
    static Instant[] materializeValidity(ShopCoupon coupon, Instant now) {
        if (coupon.getValidMode() == CouponValidMode.FIXED) {
            return new Instant[] {coupon.getValidStartAt(), coupon.getValidEndAt()};
        }
        LocalDate wibDate = LocalDate.ofInstant(now, ScheduleWindow.WIB);
        Instant until = wibDate.plusDays(coupon.getValidDays())
                .atTime(RELATIVE_END_OF_DAY).atZone(ScheduleWindow.WIB).toInstant();
        return new Instant[] {now, until};
    }

    /**
     * 提交订单占用（3.2 提交事务内）：每个 token 条件更新为 LOCKED。有效性<b>按下单时刻</b>判断
     * （之后过期不影响本单）；模板 STOPPED 但未选作废的券照常可锁（D-3）。
     *
     * @throws CouponInvalidException 任一 token 影响 0 行（不可用 / 非本人 / 已被占用 / 不在有效期），
     *         带全部失效 token；调用方整个提交事务随之回滚
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long userId, long orderId, Collection<String> userCouponTokens, Instant now) {
        List<String> invalid = new ArrayList<>();
        for (String token : new LinkedHashSet<>(userCouponTokens)) {
            if (dao.lock(token, userId, orderId, now) == 0) {
                invalid.add(token);
            }
        }
        if (!invalid.isEmpty()) {
            log.info("锁券失败 orderId={} invalidCount={}", orderId, invalid.size());
            throw new CouponInvalidException(invalid);
        }
    }

    /**
     * 迟到款重新下单：把原单用过的券重新锁到新单（v1.4.2 shop Story 4.2 AC2.4，D-3 / P-2）。
     * 仅该用户券 {@code AVAILABLE} 时成功 → {@code LOCKED}、{@code locked_order_id = newOrderId}；
     * 🔴 <b>不校验有效期</b>（已过期仍可用于这张新订单，PRD FR-134.2）；不新建用户券行。
     *
     * @throws CouponInvalidException 影响 0 行（LOCKED / USED 被别单占用或用掉、VOIDED 运营作废、非本人），带该券 token；
     *         调用方整个重下单事务随之回滚
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void relockForReorder(long userId, long userCouponId, long newOrderId, Instant now) {
        if (dao.relockForReorder(userCouponId, userId, newOrderId, now) == 0) {
            // 🔴 对外只给 token；读不回（理论上不会）给空列表，不拿内部 id 充数
            List<String> tokens = userCoupons.findById(userCouponId)
                    .map(uc -> List.of(uc.getPublicToken())).orElse(List.of());
            log.info("重下单锁券失败 userCouponId={} newOrderId={}", userCouponId, newOrderId);
            throw new CouponInvalidException(tokens);
        }
    }

    /** 付款成功：该单 LOCKED → USED。幂等（重复调用影响 0 行不报错）；🔴 不校验有效期（付款前过期不影响本单）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int markUsed(long orderId, Instant now) {
        return dao.markUsed(orderId, now);
    }

    /**
     * 订单取消退券（4.1 调用，D-3）。{@code KEEP} → 不动，返回 0；{@code RETURN} → 该单 LOCKED / USED 的券：
     * 模板 STOPPED 且 {@code void_on_stop} → VOIDED，否则 → AVAILABLE，均清订单字段
     * （已过有效期的回到 AVAILABLE 后现算为过期，不另改状态）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int releaseOnCancel(long orderId, CouponReleaseMode mode, Instant now) {
        if (mode == CouponReleaseMode.KEEP) {
            return 0;
        }
        int n = dao.releaseReturn(orderId, now);
        log.info("退券 orderId={} count={}", orderId, n);
        return n;
    }

    /** 运营停用并作废（只给 {@code CouponAdminService.stop} 调）：该模板 AVAILABLE → VOIDED；LOCKED / USED 不动。 */
    @Transactional
    public int voidUnused(long couponId, Instant now) {
        int n = dao.voidUnusedByCoupon(couponId, now);
        log.info("停券作废 couponId={} count={}", couponId, n);
        return n;
    }

    /**
     * 快过期提醒置位（Story 2.3 AC5.5.2，I8「每张券只提醒一次」）：该用户 AVAILABLE、未提醒过、到期落在
     * {@code (now, until]} 的券条件置 {@code expiry_notified_at = now}。连续两天都落在 48 小时窗口的券第二天不会再被数进去。
     * 🔴 {@code MANDATORY}：调用方（{@code CouponExpiryReminderService}）在逐用户独立事务里与提醒日志同一事务。
     *
     * @return 本次置位的券的到期时刻（无则空列表）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Instant> markExpiryNotified(long userId, Instant now, Instant until) {
        return dao.markExpiryNotified(userId, now, until);
    }

    /**
     * 已取消订单用过的券的退回结果（v1.4.2 shop Story 6.2 AC3.3，P-2：券状态只经本账本读）。<b>只读</b>，一次批量查询。
     *
     * <p>判定（每张券）：{@code mode = KEEP} → 不退；仍 LOCKED / USED 在本单 → 未退；VOIDED → 未退（模板停用作废）；
     * AVAILABLE → 过期（现算，D-2）为 {@code EXPIRED}，否则 {@code RETURNED}；LOCKED / USED 在别的订单（退回后又被用掉，
     * 含迟到款重下单重新锁券）→ {@code RETURNED}。多张券：任一 {@code EXPIRED} → {@code EXPIRED}；否则任一 {@code RETURNED}
     * → {@code RETURNED}；否则 {@code NOT_RETURNED}。
     *
     * @param userCouponIds 订单头的满减券 / 免运券 id（可含 null）
     * @return 未用券为 null
     */
    @Transactional(readOnly = true)
    public com.tailtopia.shop.coupon.domain.CouponReturnStatus couponReturnStatusOf(long orderId,
            Collection<Long> userCouponIds, CouponReleaseMode mode, Instant now) {
        List<Long> ids = userCouponIds == null ? List.of()
                : userCouponIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return null;
        }
        if (mode == CouponReleaseMode.KEEP) {
            return com.tailtopia.shop.coupon.domain.CouponReturnStatus.NOT_RETURNED;
        }
        boolean anyReturned = false;
        boolean anyExpired = false;
        for (UserCoupon uc : userCoupons.findByIdIn(ids)) {
            switch (uc.getStatus()) {
                case AVAILABLE -> {
                    if (uc.isExpiredAt(now)) {
                        anyExpired = true;
                    } else {
                        anyReturned = true;
                    }
                }
                case LOCKED, USED -> {
                    boolean stillHere = Long.valueOf(orderId).equals(uc.getLockedOrderId())
                            || Long.valueOf(orderId).equals(uc.getUsedOrderId());
                    if (!stillHere) {
                        anyReturned = true;
                    }
                }
                case VOIDED -> {
                    // 退回时模板已停用作废：对用户等于没退
                }
            }
        }
        if (anyExpired) {
            return com.tailtopia.shop.coupon.domain.CouponReturnStatus.EXPIRED;
        }
        return anyReturned ? com.tailtopia.shop.coupon.domain.CouponReturnStatus.RETURNED
                : com.tailtopia.shop.coupon.domain.CouponReturnStatus.NOT_RETURNED;
    }

    /** 注销联动（AC7）：该用户 AVAILABLE → VOIDED；LOCKED / USED 不动（订单匿名化保留）。 */
    @Transactional
    public int voidAllAvailableForUser(long userId, Instant now) {
        return dao.voidAvailableByUser(userId, now);
    }
}

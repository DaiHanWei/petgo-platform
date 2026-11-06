package com.tailtopia.shop.coupon.repository;

import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 用户券<b>读</b>仓库（v1.4.2 shop Story 2.1 AC2.3）。
 *
 * <p>🔴 刻意只继承 {@link Repository}（不继承 {@code CrudRepository} / {@code JpaRepository}）：天然没有
 * {@code save} / {@code delete}，只放 {@code find*} / {@code count*} / {@code exists*}。
 * 所有写都经 {@code CouponLedgerService} 及其专用写 DAO（P-2，守卫测试强制）。
 */
public interface UserCouponRepository extends Repository<UserCoupon, Long> {

    Optional<UserCoupon> findById(Long id);

    Optional<UserCoupon> findByPublicToken(String publicToken);

    Optional<UserCoupon> findByPublicTokenAndUserId(String publicToken, Long userId);

    List<UserCoupon> findByPublicTokenIn(Collection<String> publicTokens);

    List<UserCoupon> findByUserIdAndStatusIn(Long userId, Collection<UserCouponStatus> statuses);

    List<UserCoupon> findByLockedOrderId(Long lockedOrderId);

    /** v1.4.2 shop Story 6.2：订单详情 {@code couponReturn} 只读批量（订单头的两个券 id）。 */
    List<UserCoupon> findByIdIn(Collection<Long> ids);

    /** 每人限领（D-2）：在领取事务内、模板行已 {@code FOR UPDATE} 后调用。 */
    long countByUserIdAndCouponId(Long userId, Long couponId);

    boolean existsByUserIdAndCouponId(Long userId, Long couponId);

    /** 该用户在这批模板下各有几行（任何状态都算，Story 2.2「已领」判定）：{@code [couponId, count]}，一次查询。 */
    @Query("select uc.couponId, count(uc) from UserCoupon uc "
            + "where uc.userId = :userId and uc.couponId in :couponIds group by uc.couponId")
    List<Object[]> countByUserGroupedByCoupon(@Param("userId") Long userId,
            @Param("couponIds") Collection<Long> couponIds);

    /** 某来源、AVAILABLE 且在有效期内的用户券（Story 2.2 首页新人券置顶）。 */
    @Query("select uc from UserCoupon uc where uc.userId = :userId and uc.source = :source "
            + "and uc.status = com.tailtopia.shop.coupon.domain.UserCouponStatus.AVAILABLE "
            + "and uc.validFrom <= :now and uc.validUntil > :now order by uc.claimedAt desc, uc.id desc")
    List<UserCoupon> findUsableBySource(@Param("userId") Long userId, @Param("source") UserCouponSource source,
            @Param("now") Instant now);

    /**
     * 我的券 · 可用页签（Story 2.2 AC8 / C-3）：AVAILABLE 且在有效期内 {@code valid_from ≤ now < valid_until}，外加 LOCKED（被待付款订单占用，
     * App 置灰）；keyset {@code (claimed_at, id)} 倒序。
     */
    @Query("select uc from UserCoupon uc where uc.userId = :userId "
            + "and ((uc.status = com.tailtopia.shop.coupon.domain.UserCouponStatus.AVAILABLE "
            + "and uc.validFrom <= :now and uc.validUntil > :now) "
            + "or uc.status = com.tailtopia.shop.coupon.domain.UserCouponStatus.LOCKED) "
            + "and (uc.claimedAt < :ts or (uc.claimedAt = :ts and uc.id < :id)) "
            + "order by uc.claimedAt desc, uc.id desc")
    List<UserCoupon> pageAvailable(@Param("userId") Long userId, @Param("now") Instant now,
            @Param("ts") Instant cursorTs, @Param("id") long cursorId, Pageable pageable);

    /** 我的券 · 已使用页签（Story 2.2 AC8）：USED；keyset {@code (updated_at, id)} 倒序。 */
    @Query("select uc from UserCoupon uc where uc.userId = :userId "
            + "and uc.status = com.tailtopia.shop.coupon.domain.UserCouponStatus.USED "
            + "and (uc.updatedAt < :ts or (uc.updatedAt = :ts and uc.id < :id)) "
            + "order by uc.updatedAt desc, uc.id desc")
    List<UserCoupon> pageUsed(@Param("userId") Long userId, @Param("ts") Instant cursorTs,
            @Param("id") long cursorId, Pageable pageable);

    /**
     * 结算候选券（Story 3.1 AC1.6）：AVAILABLE 且在有效期内 {@code valid_from ≤ now < valid_until}；
     * LOCKED / USED / VOIDED / 已过期一律不出现。按到期时间升序（先到期在前）。
     */
    @Query("select uc from UserCoupon uc where uc.userId = :userId "
            + "and uc.status = com.tailtopia.shop.coupon.domain.UserCouponStatus.AVAILABLE "
            + "and uc.validFrom <= :now and uc.validUntil > :now order by uc.validUntil asc, uc.id asc")
    List<UserCoupon> findUsable(@Param("userId") Long userId, @Param("now") Instant now);

    /** 可用券张数（Story 2.2 AC8.3 / AC8.4）：AVAILABLE 且 {@code valid_from ≤ now < valid_until}（不含 LOCKED）。 */
    @Query("select count(uc) from UserCoupon uc where uc.userId = :userId "
            + "and uc.status = com.tailtopia.shop.coupon.domain.UserCouponStatus.AVAILABLE "
            + "and uc.validFrom <= :now and uc.validUntil > :now")
    long countUsable(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * 快过期提醒候选用户（Story 2.3 AC5.4，走部分索引 {@code idx_user_coupons_expiry_scan}）：有 AVAILABLE、未提醒过、
     * 到期落在 {@code (now, until]} 的券的用户；按 {@code user_id} keyset 分批（{@code afterUserId} 起，不含）。
     */
    @Query(value = "SELECT DISTINCT user_id FROM user_coupons WHERE status = 'AVAILABLE' AND expiry_notified_at IS NULL "
            + "AND valid_until > :now AND valid_until <= :until AND user_id > :afterUserId "
            + "ORDER BY user_id LIMIT :limit", nativeQuery = true)
    List<Long> findExpiryReminderUserIds(@Param("now") Instant now, @Param("until") Instant until,
            @Param("afterUserId") long afterUserId, @Param("limit") int limit);
}

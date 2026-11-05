package com.tailtopia.shop.coupon.repository;

import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

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

    /** 每人限领（D-2）：在领取事务内、模板行已 {@code FOR UPDATE} 后调用。 */
    long countByUserIdAndCouponId(Long userId, Long couponId);

    boolean existsByUserIdAndCouponId(Long userId, Long couponId);
}

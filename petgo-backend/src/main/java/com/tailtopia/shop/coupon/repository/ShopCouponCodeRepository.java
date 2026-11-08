package com.tailtopia.shop.coupon.repository;

import com.tailtopia.shop.coupon.domain.ShopCouponCode;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 兑换码仓储（v1.4.2 shop Story 2.1 AC2.6，D-4）。入参 code 一律已大写规范化。 */
public interface ShopCouponCodeRepository extends JpaRepository<ShopCouponCode, Long> {

    Optional<ShopCouponCode> findByCode(String code);

    boolean existsByCode(String code);

    @Query("select c.code from ShopCouponCode c where c.code in :codes")
    Set<String> findExistingCodes(@Param("codes") Collection<String> codes);

    /** 一次性码条件核销：影响 0 行 = 已被使用（或不是一次性码）。 */
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE shop_coupon_codes SET redeemed_by_user_id = :uid, redeemed_at = :now "
            + "WHERE id = :id AND single_use AND redeemed_at IS NULL")
    int redeemSingleUse(@Param("id") Long id, @Param("uid") Long userId, @Param("now") Instant now);

    /** 某券模板下某类码的数量（V1.4.2 shop-admin Story 2.1：批量码券加总量时补生成差额）。 */
    long countByCouponIdAndSingleUse(Long couponId, boolean singleUse);

    /** 该模板是否有任何码（改发放方式前校验）。 */
    boolean existsByCouponId(Long couponId);

    /** 导出一次性码（按生成顺序）。🔒 调用方不得记日志 / 审计码明文。 */
    List<ShopCouponCode> findByCouponIdAndSingleUseOrderByIdAsc(Long couponId, boolean singleUse);
}

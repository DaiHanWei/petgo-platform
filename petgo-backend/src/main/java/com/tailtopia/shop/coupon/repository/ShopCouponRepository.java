package com.tailtopia.shop.coupon.repository;

import com.tailtopia.shop.coupon.domain.ShopCoupon;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 券模板仓储（v1.4.2 shop Story 2.1 AC2.5，D-1）。对外一律按 public_token 寻址。 */
public interface ShopCouponRepository extends JpaRepository<ShopCoupon, Long> {

    Optional<ShopCoupon> findByPublicToken(String publicToken);

    /** {@code SELECT … FOR UPDATE}：领取 / 兑换 / 新人发放串行同一模板（D-2 每人限领的并发保护）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ShopCoupon c where c.id = :id")
    Optional<ShopCoupon> findLockedById(@Param("id") Long id);

    /** 条件扣总量（D-1）：影响 0 行 = 领完（或模板已停用）。 */
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE shop_coupons SET issued_count = issued_count + 1, updated_at = now() "
            + "WHERE id = :id AND status = 'ACTIVE' "
            + "AND (total_quantity IS NULL OR issued_count < total_quantity)")
    int incrementIssuedIfAvailable(@Param("id") Long id);

    /**
     * 条件停券（D-3，Story 2.1 复审修复）：只有 ACTIVE 的行会被改成 STOPPED。返回影响行数：
     * 1 = 本次停用（调用方据此作废已领未用、写审计）；0 = 已停用（幂等返回，不改 {@code void_on_stop} / {@code stopped_at}）。
     * 不读后写，并发停券只有一个生效。
     */
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE shop_coupons SET status = 'STOPPED', void_on_stop = :voidUnused, "
            + "stopped_at = :now, updated_at = :now WHERE id = :id AND status = 'ACTIVE'")
    int stopIfActive(@Param("id") Long id, @Param("voidUnused") boolean voidUnused, @Param("now") Instant now);

    /**
     * 新人券唯一（AB-24 / FR-130.5）：是否存在 ACTIVE 且发放期与 {@code [newStart, newEnd)} 重叠的新人券。
     * 须在 {@code CouponNewbieLock} 已加锁的事务内调用。
     */
    @Query("select count(c) > 0 from ShopCoupon c "
            + "where c.acquireType = com.tailtopia.shop.coupon.domain.CouponAcquireType.NEWBIE "
            + "and c.status = com.tailtopia.shop.coupon.domain.CouponStatus.ACTIVE "
            + "and c.issueStartAt < :newEnd and c.issueEndAt > :newStart")
    boolean existsActiveNewbieOverlapping(@Param("newStart") Instant newStart, @Param("newEnd") Instant newEnd);
}

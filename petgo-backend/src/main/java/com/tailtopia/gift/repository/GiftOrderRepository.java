package com.tailtopia.gift.repository;

import com.tailtopia.gift.domain.GiftOrder;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 送礼订单仓储（V1.4.0 gift Story 1.1）。
 *
 * <p>🔴 两条条件更新只给 {@code GiftDeliveryService} 用：{@code WHERE status = 'PENDING'} 是「回调与轮询只交付一次」
 * 的状态守卫，返回 0 = 已被并发处理，调用方必须直接返回（不写汇总、不发事件）。
 */
public interface GiftOrderRepository extends JpaRepository<GiftOrder, Long> {

    Optional<GiftOrder> findByIdemKey(String idemKey);

    Optional<GiftOrder> findByPaymentIntentId(long paymentIntentId);

    Optional<GiftOrder> findByPublicToken(String publicToken);

    /**
     * 行锁读订单（{@code SELECT … FOR UPDATE}，Story 1.2 · AC1.1）：退回的首次触发、重试任务与并发触发串行化，
     * 配合状态守卫与 {@code credit} 的幂等键保证只入账一次。只给 {@code GiftRefundService} 用。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from GiftOrder o where o.id = :id")
    Optional<GiftOrder> findByIdForUpdate(@Param("id") long id);

    /**
     * 待重试退回的订单 id（Story 1.2 · AC3.2）：{@code UNDELIVERED_REFUND_PENDING AND refund_attempts < :maxAttempts}，
     * 按 id 升序、最多 {@code limit} 条。
     */
    @Query(value = """
            SELECT id FROM gift_orders
             WHERE status = 'UNDELIVERED_REFUND_PENDING' AND refund_attempts < :maxAttempts
             ORDER BY id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findRefundRetryIds(@Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    /** PENDING → DELIVERED（交付）。返回受影响行数：1 = 本次交付，0 = 已被并发交付 / 已非 PENDING。 */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE gift_orders SET status = 'DELIVERED', delivered_at = now()
             WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markDeliveredIfPending(@Param("id") long id);

    /** PENDING → UNDELIVERED_REFUND_PENDING + 原因（付款后无法送达，退回由 Story 1.2 做）。返回受影响行数。 */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE gift_orders SET status = 'UNDELIVERED_REFUND_PENDING', undelivered_reason = :reason
             WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markUndeliveredIfPending(@Param("id") long id, @Param("reason") String reason);
}

package com.tailtopia.gift.repository;

import com.tailtopia.gift.domain.GiftOrder;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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

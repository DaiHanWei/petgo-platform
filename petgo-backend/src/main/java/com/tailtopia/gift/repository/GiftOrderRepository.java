package com.tailtopia.gift.repository;

import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftOrderStatus;
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

    /**
     * 已关单（EXPIRED / FAILED）的订单又到账了（OCR #7）→ UNDELIVERED_REFUND_PENDING，走与「付款后无法送达」同一条退回路径
     * （退 PawCoin，Story 1.2）。{@code undelivered_reason} 留空：三个既有原因都不对口，加新值要重建 CHECK，这里不值得；
     * 后台列表对空原因显示空白，WARN 日志可追溯。返回受影响行数（0 = 已被并发处理 / 状态不对）。
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE gift_orders SET status = 'UNDELIVERED_REFUND_PENDING'
             WHERE id = :id AND status IN ('EXPIRED', 'FAILED')
            """, nativeQuery = true)
    int markRefundPendingIfClosed(@Param("id") long id);

    /**
     * 后台礼物抽屉的收送统计（V1.4.0 gift Story 3.1 · AC6.4）：该礼物 {@code DELIVERED} 订单的数量之和与订单数，
     * 一行两列 {@code [received, sent]}。只读；{@code gift_id} 无专门索引，礼物百级 / 订单千级顺序扫可接受。
     */
    @Query(value = """
            SELECT COALESCE(SUM(quantity), 0) AS received, COUNT(*) AS sent
              FROM gift_orders
             WHERE gift_id = :giftId AND status = 'DELIVERED'
            """, nativeQuery = true)
    List<Object[]> deliveredTotalsOfGift(@Param("giftId") long giftId);

    /**
     * 通知列表「送的是什么」（V1.4.0 gift L2 第二轮，{@code GiftNotificationGiftLines}）：一页涉及的宠物 × 送礼人、
     * 在 [{@code since}, {@code until}] 内已送达的订单。结果在内存里再按每条通知的条件归属；一页 ≤ 20 条通知，量很小。
     */
    @Query("select o from GiftOrder o where o.petId in :petIds and o.senderId in :senderIds "
            + "and o.status = :status and o.deliveredAt >= :since and o.deliveredAt <= :until "
            + "order by o.deliveredAt asc, o.id asc")
    List<GiftOrder> findDeliveredForNotify(@Param("petIds") java.util.Collection<Long> petIds,
            @Param("senderIds") java.util.Collection<Long> senderIds, @Param("status") GiftOrderStatus status,
            @Param("since") java.time.Instant since, @Param("until") java.time.Instant until);
}

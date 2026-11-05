package com.tailtopia.shop.order.refund;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 取消退款单仓库（v1.4.2 shop Story 4.1）。 */
public interface ShopOrderRefundRepository extends JpaRepository<ShopOrderRefund, Long> {

    Optional<ShopOrderRefund> findByPublicToken(String publicToken);

    Optional<ShopOrderRefund> findByPublicTokenAndOrderId(String publicToken, long orderId);

    /** 订单详情 {@code refunds[]}：按创建时间升序。 */
    List<ShopOrderRefund> findByOrderIdOrderByCreatedAtAscIdAsc(long orderId);

    boolean existsByOrderIdAndScope(long orderId, RefundScope scope);

    /** 运营提醒（AC6.2）：某状态、早于阈值、尚未提醒过的退款单。 */
    List<ShopOrderRefund> findByStatusAndCreatedAtBeforeAndDestinationRemindedAtIsNullOrderByCreatedAtAsc(
            ShopRefundStatus status, Instant threshold);

    /** 只读查询（后台线展示）：某状态、早于阈值的退款单（不论是否已提醒）。 */
    List<ShopOrderRefund> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(ShopRefundStatus status,
            Instant threshold);


    /**
     * 🔒 注销（D1/D2）：该用户全部退款单的加密收款账号 / 户名置空（行保留供财务追溯），同 {@code return_requests}。
     */
    @Modifying
    @Query("""
            UPDATE ShopOrderRefund r
            SET r.payoutAccount = NULL, r.payoutAccountHolder = NULL, r.updatedAt = CURRENT_TIMESTAMP
            WHERE r.orderId IN (SELECT o.id FROM ShopOrder o WHERE o.userId = :userId)
              AND (r.payoutAccount IS NOT NULL OR r.payoutAccountHolder IS NOT NULL)
            """)
    int clearPayoutPiiByUserId(@Param("userId") long userId);
}

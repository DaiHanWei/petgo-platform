package com.tailtopia.shop.order.repository;

import com.tailtopia.shop.order.domain.Shipment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 包裹仓储（Story 4.1，S-2 一单多包；v1.4.2 shop Story 5.1 加 Biteship 查询）。 */
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findByShopOrderIdOrderByIdAsc(long shopOrderId);

    /** 同一承运商的同一单号只该存在一条（库级唯一索引的应用侧前置检查，给出可读错误）。 */
    Optional<Shipment> findByCarrierAndTrackingNo(String carrier, String trackingNo);

    long countByShopOrderId(long shopOrderId);

    /** Webhook 回查定位运单（v1.4.2 shop 5.1）。 */
    Optional<Shipment> findByBiteshipOrderId(String biteshipOrderId);

    /** 活动运单（未被取代、未取消），按 id 倒序（最新在前）。 */
    @Query("""
            SELECT s FROM Shipment s
            WHERE s.shopOrderId = :orderId AND s.supersededAt IS NULL
              AND s.status <> com.tailtopia.shop.order.domain.ShipmentStatus.CANCELLED
            ORDER BY s.id DESC
            """)
    List<Shipment> findActiveByShopOrderId(@Param("orderId") long shopOrderId);

    /** 最新活动运单（订单详情 {@code logistics}）。 */
    default Optional<Shipment> findLatestByShopOrderId(long shopOrderId) {
        return findActiveByShopOrderId(shopOrderId).stream().findFirst();
    }

    /** 在途回查（AC7.5）：Biteship 活动且未签收 / 未取消的运单，按状态时刻升序（最久未更新的先查）。 */
    @Query("""
            SELECT s FROM Shipment s
            WHERE s.provider = com.tailtopia.shop.order.domain.ShipmentProvider.BITESHIP
              AND s.supersededAt IS NULL
              AND s.status IN (com.tailtopia.shop.order.domain.ShipmentStatus.CREATED,
                               com.tailtopia.shop.order.domain.ShipmentStatus.SHIPPED)
            ORDER BY s.providerStatusAt ASC NULLS FIRST, s.id ASC
            """)
    List<Shipment> findBiteshipInFlight(Pageable pageable);

    /**
     * 停滞候选（v1.4.2 shop 5.2 AC3.2，走 {@code ix_shipments_biteship_active}）：Biteship 活动运单在 {@code cutoff} 之前就没有新状态，
     * 且所属订单待发货 / 已发货、无物流异常；已有其他处置的 Biteship 状态不参与。手工运单（{@code MANUAL}）不参与。
     * 5.2 复审 2：判据为 {@code max(provider_status_at, 订单 timer_resumed_at) < cutoff}——运营清除停滞后重新等满阈值才会再标。
     */
    @Query(value = """
            SELECT DISTINCT o.id FROM shipments s JOIN shop_orders o ON o.id = s.shop_order_id
            WHERE s.provider = 'BITESHIP' AND s.superseded_at IS NULL AND s.status IN ('CREATED','SHIPPED')
              AND s.provider_status_at < :cutoff
              AND (s.provider_status IS NULL OR s.provider_status NOT IN
                   ('cancelled','rejected','disposed','returned','courier_not_found'))
              AND o.status IN ('PENDING_SHIPMENT','SHIPPED') AND o.logistics_exception IS NULL
              AND (o.timer_resumed_at IS NULL OR o.timer_resumed_at < :cutoff)
            ORDER BY o.id LIMIT :limit
            """, nativeQuery = true)
    List<Long> findStallCandidateOrderIds(@Param("cutoff") java.time.Instant cutoff, @Param("limit") int limit);
}

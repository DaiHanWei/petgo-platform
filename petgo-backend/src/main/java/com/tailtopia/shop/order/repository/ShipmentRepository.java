package com.tailtopia.shop.order.repository;

import com.tailtopia.shop.order.domain.Shipment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 包裹仓储（Story 4.1，S-2 一单多包）。 */
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findByShopOrderIdOrderByIdAsc(long shopOrderId);

    /** 同一承运商的同一单号只该存在一条（库级唯一索引的应用侧前置检查，给出可读错误）。 */
    Optional<Shipment> findByCarrierAndTrackingNo(com.tailtopia.shop.order.domain.Carrier carrier,
            String trackingNo);

    long countByShopOrderId(long shopOrderId);
}

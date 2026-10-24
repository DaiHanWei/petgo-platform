package com.tailtopia.merchant.po.repository;

import com.tailtopia.merchant.po.domain.PurchaseOrderShipment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderShipmentRepository extends JpaRepository<PurchaseOrderShipment, Long> {

    /** 契约 5.3：按 {@code shipped_at} 升序（同刻按 id）。 */
    List<PurchaseOrderShipment> findByPurchaseOrderIdOrderByShippedAtAscIdAsc(Long purchaseOrderId);
}

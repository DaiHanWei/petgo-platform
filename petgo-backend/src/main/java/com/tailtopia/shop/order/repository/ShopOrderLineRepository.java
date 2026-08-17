package com.tailtopia.shop.order.repository;

import com.tailtopia.shop.order.domain.ShopOrderLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 订单行仓储（Story 3.2）。 */
public interface ShopOrderLineRepository extends JpaRepository<ShopOrderLine, Long> {
    List<ShopOrderLine> findByOrderIdOrderByIdAsc(long orderId);
}

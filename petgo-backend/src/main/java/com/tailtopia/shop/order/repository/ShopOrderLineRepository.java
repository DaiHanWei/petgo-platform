package com.tailtopia.shop.order.repository;

import com.tailtopia.shop.order.domain.ShopOrderLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 订单行仓储（Story 3.2）。 */
public interface ShopOrderLineRepository extends JpaRepository<ShopOrderLine, Long> {
    List<ShopOrderLine> findByOrderIdOrderByIdAsc(long orderId);

    /**
     * 该用户在 {@code after} 之后是否又买过这个 SKU 并已付款（Story 6.3）。
     *
     * <p>🔴 复购触发的失效判据：<b>再次购买后旧触发立即失效，按新订单重新起算</b>。
     * ⚠️ 这里刻意做成<b>读时判定</b>（与支付窗的懒过期同范式），而不是在支付链路上挂钩子 ——
     * Epic 3 的支付链路是资金与库存的同事务临界区，Epic 6 不该往里面塞自己的副作用。
     */
    @Query("""
            SELECT COUNT(l) > 0 FROM ShopOrderLine l, ShopOrder o
            WHERE l.orderId = o.id AND o.userId = :userId AND l.skuId = :skuId
              AND o.createdAt > :after
              AND o.status <> com.tailtopia.shop.order.domain.ShopOrderStatus.PENDING_PAYMENT
              AND o.status <> com.tailtopia.shop.order.domain.ShopOrderStatus.CANCELLED
            """)
    boolean existsPaidLineForSkuAfter(@Param("userId") long userId, @Param("skuId") long skuId,
            @Param("after") java.time.Instant after);
}

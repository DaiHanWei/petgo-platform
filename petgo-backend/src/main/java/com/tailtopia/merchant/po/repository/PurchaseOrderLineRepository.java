package com.tailtopia.merchant.po.repository;

import com.tailtopia.merchant.po.domain.PurchaseOrderLine;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, Long> {

    List<PurchaseOrderLine> findByPurchaseOrderIdOrderByLineNoAsc(Long purchaseOrderId);

    /** 列表行的第一行明细（{@code line_no = 1}），一页一次查询，不 N+1。 */
    List<PurchaseOrderLine> findByPurchaseOrderIdInAndLineNo(Collection<Long> purchaseOrderIds, int lineNo);

    /** 列表行的明细行数：{@code [purchase_order_id, count]}。 */
    @Query("select l.purchaseOrderId, count(l) from PurchaseOrderLine l "
            + "where l.purchaseOrderId in :ids group by l.purchaseOrderId")
    List<Object[]> countByPurchaseOrderIds(@Param("ids") Collection<Long> purchaseOrderIds);
}

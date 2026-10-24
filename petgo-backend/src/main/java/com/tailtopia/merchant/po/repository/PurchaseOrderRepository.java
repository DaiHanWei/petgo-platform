package com.tailtopia.merchant.po.repository;

import com.tailtopia.merchant.po.domain.PurchaseOrder;
import com.tailtopia.merchant.po.domain.PurchaseOrderStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    /** 🔴 门店隔离：单个进货单一律按 (门店, token) 查，不匹配即 404，不区分「不存在」与「不是你的」。 */
    Optional<PurchaseOrder> findByPublicTokenAndMerchantStoreId(String publicToken, Long merchantStoreId);

    Optional<PurchaseOrder> findByPublicToken(String publicToken);

    Optional<PurchaseOrder> findByPaymentIntentToken(String paymentIntentToken);

    boolean existsByOrderNo(String orderNo);

    /** 接口 4：按 created_at 倒序、同刻按 id 倒序（索引 idx_purchase_orders_store_created）。 */
    @Query(value = "select o from PurchaseOrder o where o.merchantStoreId = :store "
            + "order by o.createdAt desc, o.id desc",
            countQuery = "select count(o) from PurchaseOrder o where o.merchantStoreId = :store")
    Page<PurchaseOrder> pageByStore(@Param("store") long storeId, Pageable pageable);

    @Query(value = "select o from PurchaseOrder o where o.merchantStoreId = :store and o.status = :status "
            + "order by o.createdAt desc, o.id desc",
            countQuery = "select count(o) from PurchaseOrder o "
            + "where o.merchantStoreId = :store and o.status = :status")
    Page<PurchaseOrder> pageByStoreAndStatus(@Param("store") long storeId,
            @Param("status") PurchaseOrderStatus status, Pageable pageable);

    /** 接口 5：已付款进货单数与商品金额合计。 */
    @Query("select count(o), coalesce(sum(o.goodsAmount), 0) from PurchaseOrder o "
            + "where o.merchantStoreId = :store and o.status in :statuses")
    List<Object[]> summarize(@Param("store") long storeId,
            @Param("statuses") Collection<PurchaseOrderStatus> statuses);

    /** 该门店已过付款截止仍待付款的单（读接口前惰性取消）。 */
    @Query("select o from PurchaseOrder o where o.merchantStoreId = :store "
            + "and o.status = com.tailtopia.merchant.po.domain.PurchaseOrderStatus.PENDING_PAYMENT "
            + "and o.paymentDeadline <= :now")
    List<PurchaseOrder> findOverdueForStore(@Param("store") long storeId, @Param("now") Instant now);

    /** 超时扫描兜底（索引 idx_purchase_orders_pending_deadline）。 */
    @Query("select o.id from PurchaseOrder o "
            + "where o.status = com.tailtopia.merchant.po.domain.PurchaseOrderStatus.PENDING_PAYMENT "
            + "and o.paymentDeadline <= :now order by o.paymentDeadline")
    List<Long> findOverdueIds(@Param("now") Instant now, Pageable pageable);

    /** 发货起超时仍未送达。 */
    @Query("select o.id from PurchaseOrder o "
            + "where o.status = com.tailtopia.merchant.po.domain.PurchaseOrderStatus.SHIPPED "
            + "and o.shippedAt < :before order by o.shippedAt")
    List<Long> findAutoDeliverDueIds(@Param("before") Instant before, Pageable pageable);

    /** 送达起超时仍未完成。 */
    @Query("select o.id from PurchaseOrder o "
            + "where o.status = com.tailtopia.merchant.po.domain.PurchaseOrderStatus.DELIVERED "
            + "and o.deliveredAt < :before order by o.deliveredAt")
    List<Long> findAutoCompleteDueIds(@Param("before") Instant before, Pageable pageable);

    /** ops 列表：可按状态筛选、按单号模糊搜（全部门店）。不搜时 {@code orderNoPattern} 传 {@code "%"}。 */
    @Query(value = "select o from PurchaseOrder o where (:status is null or o.status = :status) "
            + "and o.orderNo like :q order by o.createdAt desc, o.id desc",
            countQuery = "select count(o) from PurchaseOrder o where (:status is null or o.status = :status) "
            + "and o.orderNo like :q")
    Page<PurchaseOrder> adminSearch(@Param("status") PurchaseOrderStatus status, @Param("q") String orderNoPattern,
            Pageable pageable);

    long countByStatus(PurchaseOrderStatus status);

    /** ops 列表状态 chips 的计数：一条 group by 出全部状态，{@code [status, count]}（只读）。 */
    @Query("select o.status, count(o) from PurchaseOrder o group by o.status")
    List<Object[]> countGroupByStatus();
}

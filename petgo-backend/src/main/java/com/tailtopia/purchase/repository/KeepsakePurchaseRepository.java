package com.tailtopia.purchase.repository;

import com.tailtopia.purchase.domain.KeepsakePurchase;
import com.tailtopia.purchase.domain.KeepsakePurchaseStatus;
import com.tailtopia.purchase.domain.KeepsakeSku;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KeepsakePurchaseRepository extends JpaRepository<KeepsakePurchase, Long> {

    Optional<KeepsakePurchase> findByPaymentIntentId(long paymentIntentId);

    Optional<KeepsakePurchase> findFirstBySkuAndRefIdAndStatus(KeepsakeSku sku, long refId,
            KeepsakePurchaseStatus status);

    Optional<KeepsakePurchase> findByPublicToken(String publicToken);

    /**
     * 订单中心（V1.3.2 Story 3.6 · AC2.1）：与 {@code ShopOrderRepository.findOrderCenterPageBefore} <b>同形</b>——
     * 排序键 {@code (created_at DESC, id DESC)} 与订单中心归并全序逐字一致。
     */
    @Query("""
            SELECT k FROM KeepsakePurchase k
            WHERE k.userId = :userId
              AND k.status IN :statuses
              AND k.sku IN :skus
              AND (k.createdAt < :beforeTs
                   OR (k.createdAt = :beforeTs AND k.id < :beforeId))
            ORDER BY k.createdAt DESC, k.id DESC
            """)
    List<KeepsakePurchase> findOrderCenterPageBefore(@Param("userId") long userId,
            @Param("statuses") Collection<KeepsakePurchaseStatus> statuses,
            @Param("skus") Collection<KeepsakeSku> skus,
            @Param("beforeTs") Instant beforeTs,
            @Param("beforeId") long beforeId, Pageable pageable);

    /** 后台异常页（Story 3.6 · AC5）：钱已到账但未正常发放 / 重复（按 paid_at 倒序）。 */
    Page<KeepsakePurchase> findByStatusInOrderByPaidAtDescIdDesc(Collection<KeepsakePurchaseStatus> statuses,
            Pageable pageable);

    /** 后台异常页：某业务行上已到账的购买（登机牌「付过两次」行找关联购买号）。 */
    Optional<KeepsakePurchase> findFirstBySkuAndRefIdAndStatusInOrderByIdDesc(KeepsakeSku sku, long refId,
            Collection<KeepsakePurchaseStatus> statuses);
}

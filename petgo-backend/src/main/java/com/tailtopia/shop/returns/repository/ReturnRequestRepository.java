package com.tailtopia.shop.returns.repository;

import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.returns.domain.ReturnStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 退货申请仓储（Story 5.1）。 */
public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {

    /** 🔴 越权与不存在同为 404（与订单同口径）：双条件查。 */
    Optional<ReturnRequest> findByPublicTokenAndUserId(String publicToken, long userId);

    Optional<ReturnRequest> findByPublicToken(String publicToken);

    List<ReturnRequest> findByShopOrderIdOrderByIdDesc(long shopOrderId);

    List<ReturnRequest> findByUserIdOrderByCreatedAtDescIdDesc(long userId);

    /**
     * 该订单当前进行中的申请（C-12：至多一张）。
     *
     * <p>⚠️ 本查询只是<b>展示与提示</b>用（订单详情页的退货入口置灰）。
     * 🔴 <b>并发正确性靠库级部分唯一索引</b> {@code uq_return_requests_active_per_order}，
     * 不靠这个查询 —— 「查一下有没有」与「插入」之间永远有窗口。
     */
    @Query("""
            SELECT r FROM ReturnRequest r
            WHERE r.shopOrderId = :orderId AND r.status IN :active
            """)
    List<ReturnRequest> findActiveByOrder(@Param("orderId") long orderId,
            @Param("active") java.util.Collection<ReturnStatus> active);

    default Optional<ReturnRequest> findActiveByOrder(long orderId) {
        return findActiveByOrder(orderId, ReturnStatus.ACTIVE).stream().findFirst();
    }

    /** 后台审核队列（AB-12A）。 */
    List<ReturnRequest> findByStatusOrderByCreatedAtAscIdAsc(ReturnStatus status,
            Pageable pageable);

    List<ReturnRequest> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /** 超 7 日未寄回的申请（S-7：到期关闭）。 */
    @Query("""
            SELECT r FROM ReturnRequest r
            WHERE r.status = com.tailtopia.shop.returns.domain.ReturnStatus.AWAIT_SHIPBACK
              AND r.shipbackDeadline IS NOT NULL AND r.shipbackDeadline < :now
            ORDER BY r.shipbackDeadline ASC
            """)
    List<ReturnRequest> findShipbackOverdue(@Param("now") Instant now, Pageable pageable);
}

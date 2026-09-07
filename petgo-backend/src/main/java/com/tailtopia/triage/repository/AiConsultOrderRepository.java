package com.tailtopia.triage.repository;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.triage.domain.AiConsultOrder;
import com.tailtopia.triage.domain.AiConsultOrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * AI 解锁订单仓储（Story 2.3）。{@link #findByPaymentIntentToken} 供现金到账处理器按 intent 反查订单
 * 拿 {@code triageTaskId} 去解锁（intent↔triage 关联锚）。
 */
public interface AiConsultOrderRepository extends JpaRepository<AiConsultOrder, Long> {

    Optional<AiConsultOrder> findByPaymentIntentToken(String paymentIntentToken);

    /** 订单详情按 token 定位（Story 5.3）。 */
    Optional<AiConsultOrder> findByOrderToken(String orderToken);

    /**
     * 订单中心游标分页（Story 5.1）：本人 COMPLETED 订单，游标 {@code (beforeTs, beforeId)} 之后的一页，
     * 排序 {@code created_at DESC, id DESC}。
     *
     * <p>🔴🔴 <b>游标必须是 {@code (created_at, id)} 复合的</b>（2026-08-18 修，
     * {@code action_items: ORDER-CENTER-CURSOR-TIE}）。原来是 {@code created_at < cursor}
     * 且游标截断到毫秒 —— 落在同一毫秒里的订单会在分页边界被<b>整批跳过</b>，用户再也看不到。
     *
     * <p>⚠️ {@code beforeId} 由 {@code OrderCenterService} 按<b>源的先后位</b>给哨兵值：
     * 排在游标源之前的源传 {@code Long.MIN_VALUE}（同刻组已发完 → 退化为严格 {@code <}），
     * 之后的源传 {@code Long.MAX_VALUE}（同刻组还没发 → 等价 {@code <=}），
     * 游标自己那个源传真实 id。这样<b>一条查询覆盖三种边界</b>，
     * 且排序键与归并层的全序<b>逐字一致</b> —— 两处不一致就是漏单的来源。
     */
    @Query("""
            SELECT o FROM AiConsultOrder o
            WHERE o.userId = :userId
              AND o.status = :status
              AND (o.createdAt < :beforeTs
                   OR (o.createdAt = :beforeTs AND o.id < :beforeId))
            ORDER BY o.createdAt DESC, o.id DESC
            """)
    List<AiConsultOrder> findPageBefore(@Param("userId") long userId,
            @Param("status") AiConsultOrderStatus status, @Param("beforeTs") Instant beforeTs,
            @Param("beforeId") long beforeId, Pageable pageable);

    // ── Story 9.4 后台收入统计（AB-8C）。收入口径 = COMPLETED 金额之和。──

    long countByStatus(AiConsultOrderStatus status);

    @Query("select coalesce(sum(o.amount), 0) from AiConsultOrder o where o.status = :status")
    long sumAmountByStatus(AiConsultOrderStatus status);

    @Query("select coalesce(sum(o.amount), 0) from AiConsultOrder o "
            + "where o.status = :status and o.payChannel = :channel")
    long sumAmountByStatusAndChannel(AiConsultOrderStatus status, PayChannel channel);
}

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

/**
 * AI 解锁订单仓储（Story 2.3）。{@link #findByPaymentIntentToken} 供现金到账处理器按 intent 反查订单
 * 拿 {@code triageTaskId} 去解锁（intent↔triage 关联锚）。
 */
public interface AiConsultOrderRepository extends JpaRepository<AiConsultOrder, Long> {

    Optional<AiConsultOrder> findByPaymentIntentToken(String paymentIntentToken);

    /** 订单详情按 token 定位（Story 5.3）。 */
    Optional<AiConsultOrder> findByOrderToken(String orderToken);

    /** 订单中心游标分页（Story 5.1）：本人 COMPLETED 订单 created_at < cursor 倒序（PENDING/ABNORMAL 过程态不入）。 */
    List<AiConsultOrder> findByUserIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
            long userId, AiConsultOrderStatus status, Instant cursor, Pageable pageable);

    // ── Story 9.4 后台收入统计（AB-8C）。收入口径 = COMPLETED 金额之和。──

    long countByStatus(AiConsultOrderStatus status);

    @Query("select coalesce(sum(o.amount), 0) from AiConsultOrder o where o.status = :status")
    long sumAmountByStatus(AiConsultOrderStatus status);

    @Query("select coalesce(sum(o.amount), 0) from AiConsultOrder o "
            + "where o.status = :status and o.payChannel = :channel")
    long sumAmountByStatusAndChannel(AiConsultOrderStatus status, PayChannel channel);
}

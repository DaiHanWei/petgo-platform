package com.tailtopia.triage.repository;

import com.tailtopia.triage.domain.AiConsultOrder;
import com.tailtopia.triage.domain.AiConsultOrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * AI 解锁订单仓储（Story 2.3）。{@link #findByPaymentIntentToken} 供现金到账处理器按 intent 反查订单
 * 拿 {@code triageTaskId} 去解锁（intent↔triage 关联锚）。
 */
public interface AiConsultOrderRepository extends JpaRepository<AiConsultOrder, Long> {

    Optional<AiConsultOrder> findByPaymentIntentToken(String paymentIntentToken);

    /** 订单中心游标分页（Story 5.1）：本人 COMPLETED 订单 created_at < cursor 倒序（PENDING/ABNORMAL 过程态不入）。 */
    List<AiConsultOrder> findByUserIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
            long userId, AiConsultOrderStatus status, Instant cursor, Pageable pageable);
}

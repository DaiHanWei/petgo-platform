package com.tailtopia.triage.repository;

import com.tailtopia.triage.domain.AiConsultOrder;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * AI 解锁订单仓储（Story 2.3）。{@link #findByPaymentIntentToken} 供现金到账处理器按 intent 反查订单
 * 拿 {@code triageTaskId} 去解锁（intent↔triage 关联锚）。
 */
public interface AiConsultOrderRepository extends JpaRepository<AiConsultOrder, Long> {

    Optional<AiConsultOrder> findByPaymentIntentToken(String paymentIntentToken);
}

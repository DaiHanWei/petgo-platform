package com.tailtopia.pay.refund.repository;

import com.tailtopia.pay.refund.domain.RefundRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 退款请求读写（Story 4.3）。一订单一请求（唯一 order_id，DB 约束 + service 预检）。
 */
public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

    Optional<RefundRequest> findByRefundToken(String refundToken);

    boolean existsByOrderId(long orderId);
}

package com.tailtopia.pay.refund.repository;

import com.tailtopia.pay.refund.domain.RefundRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 退款请求读写（Story 4.3）。一订单一请求（唯一 order_id，DB 约束 + service 预检）。
 */
public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

    Optional<RefundRequest> findByRefundToken(String refundToken);

    boolean existsByOrderId(long orderId);

    /** 订单详情退款子阶段派生（Story 5.3，一订单一退款）。 */
    Optional<RefundRequest> findByOrderId(long orderId);

    /** 用户端「我的退款」列表（Story 4.5，仅本人，倒序）。 */
    List<RefundRequest> findByUserIdOrderByCreatedAtDesc(long userId);

    /** 后台退款管理列表（Story 4.6，全量倒序）。 */
    List<RefundRequest> findAllByOrderByCreatedAtDesc();
}

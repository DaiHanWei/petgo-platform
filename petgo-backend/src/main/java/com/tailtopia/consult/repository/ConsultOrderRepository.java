package com.tailtopia.consult.repository;

import com.tailtopia.consult.domain.ConsultOrder;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 咨询订单仓储（Story 3.1，支付成功建的持久订单）。 */
public interface ConsultOrderRepository extends JpaRepository<ConsultOrder, Long> {

    Optional<ConsultOrder> findByOrderToken(String orderToken);
}

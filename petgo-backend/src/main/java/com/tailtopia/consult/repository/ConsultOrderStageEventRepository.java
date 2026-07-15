package com.tailtopia.consult.repository;

import com.tailtopia.consult.domain.ConsultOrderStageEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 咨询订单节点事件仓储（Story 3.1）。append-only：只 save（INSERT）+ 按订单读，绝不 update/delete 旧行。
 */
public interface ConsultOrderStageEventRepository extends JpaRepository<ConsultOrderStageEvent, Long> {

    List<ConsultOrderStageEvent> findByConsultOrderIdOrderByOccurredAtAsc(long consultOrderId);
}

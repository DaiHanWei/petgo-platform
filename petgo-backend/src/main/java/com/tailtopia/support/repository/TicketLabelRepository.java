package com.tailtopia.support.repository;

import com.tailtopia.support.domain.TicketLabel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 工单标签读写（Story 4.1）。
 */
public interface TicketLabelRepository extends JpaRepository<TicketLabel, Long> {

    List<TicketLabel> findByTicketIdOrderByIdAsc(long ticketId);
}

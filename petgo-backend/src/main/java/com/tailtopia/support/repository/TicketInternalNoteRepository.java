package com.tailtopia.support.repository;

import com.tailtopia.support.domain.TicketInternalNote;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 工单内部备注读写（Story 4.1）。**用户不可见**——仅 admin 视图（4-4）读；本 story 建 addInternalNote 原语。
 */
public interface TicketInternalNoteRepository extends JpaRepository<TicketInternalNote, Long> {

    List<TicketInternalNote> findByTicketIdOrderByIdAsc(long ticketId);
}

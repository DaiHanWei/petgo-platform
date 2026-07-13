package com.tailtopia.support.repository;

import com.tailtopia.support.domain.TicketAttachment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 工单附件读写（Story 4.1）。展示时按 ticket 取 object_key，SignedUrlService 现签。
 */
public interface TicketAttachmentRepository extends JpaRepository<TicketAttachment, Long> {

    List<TicketAttachment> findByTicketIdOrderByIdAsc(long ticketId);
}

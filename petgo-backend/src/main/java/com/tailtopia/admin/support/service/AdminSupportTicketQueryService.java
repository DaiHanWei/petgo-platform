package com.tailtopia.admin.support.service;

import com.tailtopia.admin.support.dto.AdminTicketView;
import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.domain.TicketAttachment;
import com.tailtopia.support.domain.TicketLabel;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import com.tailtopia.support.repository.TicketAttachmentRepository;
import com.tailtopia.support.repository.TicketLabelRepository;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台客服工单查询（Story 4.7，供工单管理列表/详情 SSR）。admin 处理需见联系方式/正文（已授 support.handle）。
 */
@Service
public class AdminSupportTicketQueryService {

    private final FeedbackTicketRepository tickets;
    private final TicketLabelRepository labels;
    private final TicketAttachmentRepository attachments;

    public AdminSupportTicketQueryService(FeedbackTicketRepository tickets, TicketLabelRepository labels,
            TicketAttachmentRepository attachments) {
        this.tickets = tickets;
        this.labels = labels;
        this.attachments = attachments;
    }

    @Transactional(readOnly = true)
    public List<AdminTicketView> list() {
        return tickets.findAllByOrderByCreatedAtDesc().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public AdminTicketView find(String ticketToken) {
        FeedbackTicket t = tickets.findByTicketToken(ticketToken)
                .orElseThrow(() -> AppException.notFound("工单不存在"));
        return toView(t);
    }

    private AdminTicketView toView(FeedbackTicket t) {
        List<String> labelNames = new ArrayList<>();
        for (TicketLabel l : labels.findByTicketIdOrderByIdAsc(t.getId())) {
            labelNames.add(l.getLabel().name());
        }
        List<TicketAttachment> atts = attachments.findByTicketIdOrderByIdAsc(t.getId());
        return new AdminTicketView(
                t.getTicketToken(), t.getSubject(), t.getBody(),
                t.getContactType().name(), t.getContactValue(),
                t.isNeedContactCustomer(), t.isContactedCustomer(), t.getStatus().name(),
                labelNames, atts.size(), t.getCsatScore(), t.getCsatComment(),
                t.getCreatedAt(), t.getResolvedAt());
    }
}

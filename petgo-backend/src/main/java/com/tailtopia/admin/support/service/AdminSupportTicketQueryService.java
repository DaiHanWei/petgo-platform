package com.tailtopia.admin.support.service;

import com.tailtopia.admin.support.dto.AdminTicketView;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.refund.domain.RefundRequest;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.domain.TicketAttachment;
import com.tailtopia.support.domain.TicketLabel;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import com.tailtopia.support.repository.TicketAttachmentRepository;
import com.tailtopia.support.repository.TicketLabelRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台客服工单查询（Story 4.7，供工单管理列表/详情 SSR）。admin 处理需见联系方式/正文（已授 support.handle）。
 *
 * <p>详情附带：附件签名 URL（仅详情签发，短 TTL 不缓存不入库，bug 20260728-387）、关联订单 token 与
 * 该订单退款单状态（AB-5B 客服判定前置，bug 20260728-384）。
 */
@Service
public class AdminSupportTicketQueryService {

    private final FeedbackTicketRepository tickets;
    private final TicketLabelRepository labels;
    private final TicketAttachmentRepository attachments;
    private final ConsultOrderRepository orders;
    private final RefundRequestRepository refunds;
    private final SignedUrlService signedUrls;

    public AdminSupportTicketQueryService(FeedbackTicketRepository tickets, TicketLabelRepository labels,
            TicketAttachmentRepository attachments, ConsultOrderRepository orders,
            RefundRequestRepository refunds, SignedUrlService signedUrls) {
        this.tickets = tickets;
        this.labels = labels;
        this.attachments = attachments;
        this.orders = orders;
        this.refunds = refunds;
        this.signedUrls = signedUrls;
    }

    @Transactional(readOnly = true)
    public List<AdminTicketView> list() {
        return tickets.findAllByOrderByCreatedAtDesc().stream().map(t -> toView(t, false)).toList();
    }

    @Transactional(readOnly = true)
    public AdminTicketView find(String ticketToken) {
        FeedbackTicket t = tickets.findByTicketToken(ticketToken)
                .orElseThrow(() -> AppException.notFound("工单不存在"));
        return toView(t, true);
    }

    private AdminTicketView toView(FeedbackTicket t, boolean detail) {
        List<String> labelNames = new ArrayList<>();
        for (TicketLabel l : labels.findByTicketIdOrderByIdAsc(t.getId())) {
            labelNames.add(l.getLabel().name());
        }
        List<TicketAttachment> atts = attachments.findByTicketIdOrderByIdAsc(t.getId());
        // 附件签名 URL 仅详情页签发（短 TTL，列表页无渲染点不白签）。
        List<String> attachmentUrls = List.of();
        if (detail && !atts.isEmpty()) {
            attachmentUrls = signedUrls.signAll(atts.stream().map(TicketAttachment::getObjectKey).toList());
        }
        String relatedOrderToken = null;
        String refundToken = null;
        String refundNeedDecision = null;
        if (t.getRelatedOrderId() != null) {
            relatedOrderToken = orders.findById(t.getRelatedOrderId())
                    .map(ConsultOrder::getOrderToken).orElse(null);
            RefundRequest refund = refunds.findByOrderId(t.getRelatedOrderId()).orElse(null);
            if (refund != null) {
                refundToken = refund.getRefundToken();
                refundNeedDecision = refund.getNeedDecision().name();
            }
        }
        return new AdminTicketView(
                t.getTicketToken(), t.getSubject(), t.getBody(),
                t.getContactType().name(), t.getContactValue(),
                t.isNeedContactCustomer(), t.isContactedCustomer(), t.getStatus().name(),
                labelNames, atts.size(), attachmentUrls,
                relatedOrderToken, refundToken, refundNeedDecision,
                t.getCsatScore(), t.getCsatComment(),
                t.getCreatedAt(), t.getResolvedAt());
    }
}

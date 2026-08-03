package com.tailtopia.admin.support.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.pay.refund.service.RefundService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.domain.TicketStatus;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工单侧退款判定编排（AB-5B 客服判定环节，bug 20260728-384/388 修复）。
 *
 * <p>补齐「用户提 REFUND 工单 → refund_requests 出新行」缺失的上游：客服在工单详情
 * ① 补挂关联订单（归属校验：订单必须属于工单用户）；② 批准/驳回退款需求——若该订单尚无退款单，
 * 先经 {@link RefundService#createRefundRequest} 建单（绑工单 id 溯源），再走既有
 * {@link RefundService#approveNeed}/{@link RefundService#rejectNeed}（订单 CAS/用户通知/审计均在其内）。
 */
@Service
public class AdminTicketRefundService {

    private final FeedbackTicketRepository tickets;
    private final ConsultOrderRepository orders;
    private final RefundRequestRepository refunds;
    private final RefundService refundService;
    private final AdminAuditService audit;

    public AdminTicketRefundService(FeedbackTicketRepository tickets, ConsultOrderRepository orders,
            RefundRequestRepository refunds, RefundService refundService, AdminAuditService audit) {
        this.tickets = tickets;
        this.orders = orders;
        this.refunds = refunds;
        this.refundService = refundService;
        this.audit = audit;
    }

    /** 补挂关联订单：校验订单存在且属于工单用户；仅未结案工单可挂。 */
    @Transactional
    public void linkOrder(String ticketToken, String orderToken, long adminId) {
        FeedbackTicket t = requireOpen(ticketToken);
        ConsultOrder order = orders.findByOrderToken(orderToken == null ? "" : orderToken.trim())
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        if (!order.getUserId().equals(t.getUserId())) {
            throw AppException.validation("该订单不属于本工单用户，无法关联");
        }
        t.linkRelatedOrder(order.getId());
        audit.record(adminId, AuditActions.TICKET_ORDER_LINKED, "feedback_ticket", ticketToken,
                "工单关联订单 order=" + order.getOrderToken());
    }

    /** 批准退款需求：无退款单则先建（绑工单溯源），再 approveNeed（订单 CAS COMPLETED→REFUNDING，解锁 App 选方式）。 */
    @Transactional
    public void approveRefundNeed(String ticketToken, long adminId) {
        FeedbackTicket t = requireOpen(ticketToken);
        refundService.approveNeed(ensureRefundRequest(t, adminId), adminId);
    }

    /** 驳回退款需求：无退款单则先建（留痕），再 rejectNeed（发 REFUND_REJECTED 用户通知）。 */
    @Transactional
    public void rejectRefundNeed(String ticketToken, long adminId) {
        FeedbackTicket t = requireOpen(ticketToken);
        refundService.rejectNeed(ensureRefundRequest(t, adminId), adminId);
    }

    private FeedbackTicket requireOpen(String ticketToken) {
        FeedbackTicket t = tickets.findByTicketToken(ticketToken)
                .orElseThrow(() -> AppException.notFound("工单不存在"));
        if (t.getStatus() != TicketStatus.OPEN && t.getStatus() != TicketStatus.IN_PROGRESS) {
            throw AppException.conflict("工单已结案，无法操作");
        }
        return t;
    }

    /** 取该订单既有退款单 token；没有则创建（related_ticket_id 溯源工单）。要求已关联订单。 */
    private String ensureRefundRequest(FeedbackTicket t, long adminId) {
        if (t.getRelatedOrderId() == null) {
            throw AppException.validation("请先关联订单，再判定退款需求");
        }
        return refunds.findByOrderId(t.getRelatedOrderId())
                .map(r -> r.getRefundToken())
                .orElseGet(() -> {
                    String orderToken = orders.findById(t.getRelatedOrderId())
                            .map(ConsultOrder::getOrderToken)
                            .orElseThrow(() -> AppException.notFound("关联订单不存在"));
                    return refundService.createRefundRequest(orderToken, t.getId(), adminId);
                });
    }
}

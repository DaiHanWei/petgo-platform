package com.tailtopia.admin.support.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.pay.refund.service.RefundService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.domain.RelatedOrderType;
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
    /** Story 3-2：电商订单也能挂到工单上。🔴 只用于 link，**绝不进退款链路**。 */
    private final ShopOrderRepository shopOrders;
    private final RefundRequestRepository refunds;
    private final RefundService refundService;
    private final AdminAuditService audit;

    public AdminTicketRefundService(FeedbackTicketRepository tickets, ConsultOrderRepository orders,
            ShopOrderRepository shopOrders,
            RefundRequestRepository refunds, RefundService refundService, AdminAuditService audit) {
        this.tickets = tickets;
        this.orders = orders;
        this.shopOrders = shopOrders;
        this.refunds = refunds;
        this.refundService = refundService;
        this.audit = audit;
    }

    /**
     * 补挂**问诊单**：校验订单存在且属于工单用户；仅未结案工单可挂。
     *
     * <p>Story 3-2 之前叫 {@code linkOrder}，行为逐行等价 —— 只是名字说清了它认哪一类订单。
     */
    @Transactional
    public void linkConsultOrder(String ticketToken, String orderToken, long adminId) {
        FeedbackTicket t = requireOpen(ticketToken);
        ConsultOrder order = orders.findByOrderToken(orderToken == null ? "" : orderToken.trim())
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
        if (!order.getUserId().equals(t.getUserId())) {
            throw AppException.validation("该订单不属于本工单用户，无法关联").code("admin.err.ticket.orderNotOwned");
        }
        requireNotRefundApprovedLocked(t, order.getId(), RelatedOrderType.CONSULT);
        t.linkConsultOrder(order.getId());
        audit.record(adminId, AuditActions.TICKET_ORDER_LINKED, "feedback_ticket", ticketToken,
                "工单关联订单 type=CONSULT order=" + order.getOrderToken());
    }

    /**
     * 补挂**电商订单**（Story 3-2 / AD-S7）。
     *
     * <p>🔴 <b>本方法体内不出现任何 {@code refunds} / {@code refundService} 引用</b>，
     * 这是三层守卫里的第一层（编译期就断开）：电商单本版不进退款审批链路，退款走线下。
     * 第二层是 {@link #ensureRefundRequest} 入口的类型守卫（运行期兜底，**那才是真正的护栏**），
     * 第三层是模板不渲染退款块（界面上点不到）。
     */
    @Transactional
    public void linkShopOrder(String ticketToken, String orderToken, long adminId) {
        FeedbackTicket t = requireOpen(ticketToken);
        ShopOrder order = shopOrders.findByPublicToken(orderToken == null ? "" : orderToken.trim())
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
        // 🔴 两边都是 Long（对象），必须 equals —— `!=` 是引用比较，真实 userId 一旦超出
        //    Long 的 [-128,127] 缓存区间就恒为 true，本人的电商单也会被判成「不是你的」。
        //    上面问诊单那一支用的就是 equals，两支必须写法一致。
        if (!order.getUserId().equals(t.getUserId())) {
            throw AppException.validation("该订单不属于本工单用户，无法关联").code("admin.err.ticket.orderNotOwned");
        }
        requireNotRefundApprovedLocked(t, order.getId(), RelatedOrderType.SHOP);
        t.linkShopOrder(order.getId());
        audit.record(adminId, AuditActions.TICKET_ORDER_LINKED, "feedback_ticket", ticketToken,
                "工单关联订单 type=SHOP order=" + order.getPublicToken());
    }

    /**
     * PR#34 finding #5：工单退款视图从「当前关联订单」推导 —— 已批出退款后再重关联，
     * 审批区块会重新出现，可对第二笔订单再批退款（且可经 refundToPawCoin 自助到账）。
     * 已有已批准退款的工单禁止改挂订单；确需二次退款走独立主管线并留审计。
     *
     * <p>🔴 <b>本守卫对两支 link 都生效</b>（不能靠挂个电商单绕过它）。
     *
     * <p>🔴 <b>查退款表前必须先判当前类型是 CONSULT</b>：工单当前挂的是电商单时，
     * 无条件 {@code refunds.findByOrderId(电商单 id)} 会命中一条**同号问诊单的退款请求**，
     * 于是出现「挂着电商单的工单，因为某个不相干问诊单有 APPROVED 退款而被禁止改挂」——
     * 症状很怪、很难查。这是串号 bug 的镜像，不是优化。
     */
    private void requireNotRefundApprovedLocked(FeedbackTicket t, long newOrderId,
            RelatedOrderType newType) {
        boolean sameOrder = t.getRelatedOrderId() != null
                && t.getRelatedOrderId().equals(newOrderId)
                && t.getRelatedOrderType() == newType;
        if (t.getRelatedOrderId() == null || sameOrder) {
            return;
        }
        if (t.getRelatedOrderType() != RelatedOrderType.CONSULT) {
            return; // 电商单没有 refund_requests 行，查了只会查出同号问诊单的。
        }
        boolean approvedRefundExists = refunds.findByOrderId(t.getRelatedOrderId())
                .map(r -> r.getNeedDecision() == com.tailtopia.pay.refund.domain.NeedDecision.APPROVED)
                .orElse(false);
        if (approvedRefundExists) {
            throw AppException.conflict("本工单关联订单已批准退款，禁止改挂其他订单").code("admin.err.ticket.refundApprovedLocked");
        }
    }

    /** 批准退款需求：无退款单则先建（绑工单溯源），再 approveNeed（订单 CAS COMPLETED→REFUNDING，解锁 App 选方式）。 */
    @Transactional
    public void approveRefundNeed(String ticketToken, long adminId) {
        FeedbackTicket t = requireOpen(ticketToken);
        refundService.approveNeed(ensureRefundRequest(t, adminId), adminId);
    }

    /** 驳回退款需求 + 必填原因（V1.3.0 Story 2.7，D-36）：服务层判空（≤200 字），透传到退款单 reject_reason 与审计。 */
    @Transactional
    public void rejectRefundNeed(String ticketToken, long adminId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("驳回退款需求必须填写原因").code("admin.err.ticket.rejectReasonRequired");
        }
        FeedbackTicket t = requireOpen(ticketToken);
        refundService.rejectNeed(ensureRefundRequest(t, adminId), adminId, reason.trim());
    }

    private FeedbackTicket requireOpen(String ticketToken) {
        FeedbackTicket t = tickets.findByTicketToken(ticketToken)
                .orElseThrow(() -> AppException.notFound("工单不存在").code("admin.err.ticket.notFound"));
        if (t.getStatus() != TicketStatus.OPEN && t.getStatus() != TicketStatus.IN_PROGRESS) {
            throw AppException.conflict("工单已结案，无法操作").code("admin.err.ticket.alreadyResolved");
        }
        return t;
    }

    /** 取该订单既有退款单 token；没有则创建（related_ticket_id 溯源工单）。要求已关联订单。 */
    private String ensureRefundRequest(FeedbackTicket t, long adminId) {
        if (t.getRelatedOrderId() == null) {
            throw AppException.validation("请先关联订单，再判定退款需求").code("admin.err.ticket.linkOrderFirst");
        }
        // 🔴🔴 **整条链路的雷管就在下面那行 `orders.findById`**（orders 是 ConsultOrderRepository）：
        //    工单挂的是电商单 42 时，它会捞出 consult_orders(42) —— 一条别人的问诊单 ——
        //    然后给那条订单建退款请求。**一次点击就能给无关订单开退款。**
        //    这道守卫是三层防线里唯一真正的护栏，删掉它另外两层都只是「不容易走到」。
        //    ⚠️ 位置必须在 `relatedOrderId == null` 之后、`findById` 之前。
        if (t.getRelatedOrderType() != RelatedOrderType.CONSULT) {
            throw AppException.validation("电商订单的退款不走工单审批，请按售后流程线下处理")
                    .code("admin.err.ticket.refundNotForShopOrder");
        }
        return refunds.findByOrderId(t.getRelatedOrderId())
                .map(r -> r.getRefundToken())
                .orElseGet(() -> {
                    String orderToken = orders.findById(t.getRelatedOrderId())
                            .map(ConsultOrder::getOrderToken)
                            .orElseThrow(() -> AppException.notFound("关联订单不存在").code("admin.err.ticket.linkedOrderNotFound"));
                    return refundService.createRefundRequest(orderToken, t.getId(), adminId);
                });
    }
}

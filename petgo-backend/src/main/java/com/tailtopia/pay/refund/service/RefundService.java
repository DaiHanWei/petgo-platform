package com.tailtopia.pay.refund.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.refund.domain.NeedDecision;
import com.tailtopia.pay.refund.domain.PayoutChannel;
import com.tailtopia.pay.refund.domain.RefundRequest;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款两段审批 service（Story 4.3，全系统最高危 A-1）。
 *
 * <p>三段角色分配原语：{@link #submitNeed}（客服 submitter）/ {@link #approve}（主管 approver）/
 * {@link #recordPayout}（财务 payer）。**职责分离服务层强制**：submitter/approver/payer 两两不可相等
 * （守卫为 admin_id 相等判定，与权限点正交——**含 SUPER_ADMIN 不豁免**，A-1）。违规即拒 + 独立事务留审计。
 * payout PII 经 {@code EncryptedStringConverter} 字段级加密落库。
 *
 * <p>本 story = 模型 + 受控迁移原语。实际 Midtrans 打款 / 订单回落 COMPLETED / 通知 / App UI 在 4-4/4-5/4-6。
 */
@Service
public class RefundService {

    private final RefundRequestRepository refunds;
    private final ConsultOrderRepository orders;
    private final CardTokenGenerator tokenGenerator;
    private final AdminAuditService audit;
    private final RefundAuditRecorder auditRecorder;

    public RefundService(RefundRequestRepository refunds, ConsultOrderRepository orders,
            CardTokenGenerator tokenGenerator, AdminAuditService audit, RefundAuditRecorder auditRecorder) {
        this.refunds = refunds;
        this.orders = orders;
        this.tokenGenerator = tokenGenerator;
        this.audit = audit;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 建退款请求（绑订单，快照订单金额）。一订单一请求（唯一 order_id + 预检 409）。
     * {@code relatedTicketId} 可空（FR-52 工单发起绑定，非必需）。actor 记审计。
     */
    @Transactional
    public String createRefundRequest(String orderToken, Long relatedTicketId, long actorAdminId) {
        ConsultOrder order = orders.findByOrderToken(orderToken)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        if (refunds.existsByOrderId(order.getId())) {
            throw AppException.conflict("该订单已有退款请求");
        }
        String token = tokenGenerator.generate();
        RefundRequest r = refunds.save(RefundRequest.create(
                order.getId(), relatedTicketId, order.getUserId(), token, order.getAmount()));
        audit.record(actorAdminId, AuditActions.REFUND_REQUEST_CREATED, "refund_request", token,
                "退款请求创建 order=" + orderToken);
        return r.getRefundToken();
    }

    /** ①客服判定退款需求（submitter 角色）。APPROVED→解锁选方式 / REJECTED→订单回落（行为 4-4）。 */
    @Transactional
    public void submitNeed(String refundToken, long submitterAdminId, NeedDecision decision) {
        RefundRequest r = require(refundToken);
        r.markNeedDecision(decision, submitterAdminId);
        audit.record(submitterAdminId, AuditActions.REFUND_NEED_SUBMITTED, "refund_request", refundToken,
                "退款需求判定=" + decision);
    }

    /**
     * 用户选退款方式 + 填收款（4-5 用户行为，非 admin）：**后端权威算净额** {@code net=order−fee}
     * + payout PII 加密落库，进 PENDING_APPROVAL。
     */
    @Transactional
    public void fillPayout(String refundToken, PayoutChannel channel, String payoutAccount,
            String accountHolderName) {
        RefundRequest r = require(refundToken);
        long fee = channel.fee();
        long net = Math.max(0, r.getOrderAmount() - fee);
        r.fillPayout(channel, payoutAccount, accountHolderName, fee, net);
    }

    /** ②主管审批通过（approver 角色）。**职责分离：approver ≠ submitter**（SUPER_ADMIN 不豁免）。 */
    @Transactional
    public void approve(String refundToken, long approverAdminId) {
        RefundRequest r = require(refundToken);
        guardDutySeparation(approverAdminId, r.getSubmitterAdminId(), "提交人(客服)", refundToken);
        r.approve(approverAdminId);
        audit.record(approverAdminId, AuditActions.REFUND_APPROVED, "refund_request", refundToken,
                "退款申请审批通过");
    }

    /**
     * ②财务记录打款完成（payer 角色）。**职责分离：payer ≠ submitter 且 payer ≠ approver**（SUPER_ADMIN 不豁免）。
     * 实际 Midtrans Iris 打款编排在 4-6，本 story 只记角色与终态。
     */
    @Transactional
    public void recordPayout(String refundToken, long payerAdminId) {
        RefundRequest r = require(refundToken);
        guardDutySeparation(payerAdminId, r.getSubmitterAdminId(), "提交人(客服)", refundToken);
        guardDutySeparation(payerAdminId, r.getApproverAdminId(), "审批人(主管)", refundToken);
        r.recordPayout(payerAdminId);
        audit.record(payerAdminId, AuditActions.REFUND_PAYOUT_RECORDED, "refund_request", refundToken,
                "退款打款完成");
    }

    // ---- 职责分离守卫（A-1，最高危）----

    /**
     * 守卫：actor 不可等于同一退款单上已占的另一角色 admin_id。相等 → 独立事务留审计 + 拒绝。
     * **纯 admin_id 相等判定，不看角色——SUPER_ADMIN 亦被拦（A-1 不豁免）。**
     */
    private void guardDutySeparation(long actorAdminId, Long otherAdminId, String otherRole, String refundToken) {
        if (otherAdminId != null && otherAdminId == actorAdminId) {
            auditRecorder.recordViolation(actorAdminId, refundToken,
                    "退款职责分离拦截：不可兼任「" + otherRole + "」");
            throw AppException.forbidden("退款职责分离：同一人不可兼任提交/审批/打款");
        }
    }

    private RefundRequest require(String refundToken) {
        return refunds.findByRefundToken(refundToken)
                .orElseThrow(() -> AppException.notFound("退款请求不存在"));
    }
}

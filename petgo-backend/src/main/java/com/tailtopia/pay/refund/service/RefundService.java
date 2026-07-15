package com.tailtopia.pay.refund.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.refund.domain.ApprovalStatus;
import com.tailtopia.pay.refund.domain.NeedDecision;
import com.tailtopia.pay.refund.domain.PayoutChannel;
import com.tailtopia.pay.refund.domain.RefundRequest;
import com.tailtopia.pay.refund.dto.MyRefundView;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.pay.domain.LedgerAccount;
import com.tailtopia.pay.service.LedgerLine;
import com.tailtopia.pay.service.LedgerService;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.DisburseRequest;
import com.tailtopia.shared.pay.DisburseResult;
import com.tailtopia.shared.pay.PaymentGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    private final NotificationService notifications;
    private final PawCoinWalletService wallet;
    private final PaymentGateway gateway;
    private final LedgerService ledger;

    public RefundService(RefundRequestRepository refunds, ConsultOrderRepository orders,
            CardTokenGenerator tokenGenerator, AdminAuditService audit, RefundAuditRecorder auditRecorder,
            NotificationService notifications, PawCoinWalletService wallet, PaymentGateway gateway,
            LedgerService ledger) {
        this.refunds = refunds;
        this.orders = orders;
        this.tokenGenerator = tokenGenerator;
        this.audit = audit;
        this.auditRecorder = auditRecorder;
        this.notifications = notifications;
        this.wallet = wallet;
        this.gateway = gateway;
        this.ledger = ledger;
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

    /** ①客服判定退款需求（submitter 角色，Story 4.3 底层原语）。APPROVED→解锁选方式 / REJECTED→订单回落（行为在 4-4 的 {@link #approveNeed}/{@link #rejectNeed}）。 */
    @Transactional
    public void submitNeed(String refundToken, long submitterAdminId, NeedDecision decision) {
        RefundRequest r = require(refundToken);
        r.markNeedDecision(decision, submitterAdminId);
        audit.record(submitterAdminId, AuditActions.REFUND_NEED_SUBMITTED, "refund_request", refundToken,
                "退款需求判定=" + decision);
    }

    /**
     * ①客服<b>批准</b>退款需求（Story 4.4，submitter 角色，AB-5B）。仅 {@code need_decision=PENDING} 可判定
     * （重复判定 → 409 拒绝，OPEN-1）。need→APPROVED + 订单 <b>CAS {@code COMPLETED→REFUNDING}</b>
     * （{@code markRefundingFromCompleted}，幂等：非 COMPLETED 时返 0 跳过不报错）。
     * <b>不发任何通知</b>（解锁「选方式」经 need=APPROVED，App 4-5 据此暴露入口）+ 审计。
     */
    @Transactional
    public void approveNeed(String refundToken, long submitterAdminId) {
        RefundRequest r = requirePending(refundToken);
        r.markNeedDecision(NeedDecision.APPROVED, submitterAdminId);
        orders.markRefundingFromCompleted(r.getOrderId()); // CAS 幂等：0=订单已非 COMPLETED，跳过
        audit.record(submitterAdminId, AuditActions.REFUND_NEED_APPROVED, "refund_request", refundToken,
                "退款需求批准（订单进 REFUNDING，解锁选方式）");
    }

    /**
     * ①客服<b>驳回</b>退款需求（Story 4.4，submitter 角色，A-2 UX 不撒谎）。仅 PENDING 可判定。
     * need→REJECTED + 订单 <b>置 {@code refund_rejected=true}（保持/回落 COMPLETED）</b>
     * （{@code markRefundRejected} CAS，不新增订单终态）+ 发 {@code REFUND_REJECTED} 通知给发起用户
     * （{@link NotificationService#send} 自带 REQUIRES_NEW；<b>不含金额/账号 PII</b>，targetRef=refundToken 非随机）+ 审计。
     */
    @Transactional
    public void rejectNeed(String refundToken, long submitterAdminId) {
        RefundRequest r = requirePending(refundToken);
        r.markNeedDecision(NeedDecision.REJECTED, submitterAdminId);
        orders.markRefundRejected(r.getOrderId()); // CAS 幂等：0=订单已非 COMPLETED，跳过
        // 驳回通知（AB-5B：仅驳回发，批准不发）。护栏：文案不含金额/账号；targetRef 为稳定 refundToken（非随机）。
        notifications.send(r.getUserId(), NotificationType.REFUND_REJECTED,
                "退款申请未通过", "你的退款申请未通过审核，如有疑问可在工单中联系客服。",
                NotificationType.REFUND_REJECTED.name(), refundToken);
        audit.record(submitterAdminId, AuditActions.REFUND_NEED_REJECTED, "refund_request", refundToken,
                "退款需求驳回（订单回落 COMPLETED+refund_rejected，已通知用户）");
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

    // ========== 用户端退款方式选择与填收款（Story 4.5，App 行为）==========

    /**
     * 用户端「我的退款」列表（Story 4.5，仅本人）。退款方式由订单原支付渠道决定；<b>零 PII 回显</b>
     * （不解密 payout_account/holder，只回 payoutFilled）。QRIS 附出款渠道费预览（后端权威净额）。
     */
    @Transactional(readOnly = true)
    public List<MyRefundView> listMyRefunds(long userId) {
        List<MyRefundView> views = new ArrayList<>();
        for (RefundRequest r : refunds.findByUserIdOrderByCreatedAtDesc(userId)) {
            ConsultOrder order = orders.findById(r.getOrderId()).orElse(null);
            if (order == null) {
                continue; // 订单不存在（理论不会，防御）
            }
            boolean pawcoin = order.getPayChannel() == PayChannel.PAWCOIN;
            boolean actionable = r.getNeedDecision() == NeedDecision.APPROVED && r.getApprovalStatus() == null;
            List<MyRefundView.PayoutOption> options = pawcoin ? List.of() : payoutOptions(r.getOrderAmount());
            views.add(new MyRefundView(
                    r.getRefundToken(), order.getOrderToken(), order.getPayChannel().name(),
                    pawcoin ? "INSTANT_PAWCOIN" : "REAL_MONEY",
                    r.getNeedDecision().name(),
                    r.getApprovalStatus() == null ? null : r.getApprovalStatus().name(),
                    r.getOrderAmount(), actionable, r.getApprovalStatus() != null, options));
        }
        return views;
    }

    /** QRIS 出款渠道费预览（后端权威 net = order − fee，前端仅展示）。 */
    private List<MyRefundView.PayoutOption> payoutOptions(long orderAmount) {
        List<MyRefundView.PayoutOption> options = new ArrayList<>();
        for (PayoutChannel ch : PayoutChannel.values()) {
            options.add(new MyRefundView.PayoutOption(ch.name(), ch.fee(),
                    Math.max(0, orderAmount - ch.fee())));
        }
        return options;
    }

    /**
     * PawCoin 订单即时退币（Story 4.5，用户确认，<b>不经 4-6</b>）。owner + need=APPROVED + 订单渠道=PAWCOIN 门控。
     * 全额 {@code credit(REFUND)}（无手续费、幂等键）+ 订单 CAS {@code markRefunded}（REFUNDING→REFUNDED，返 0 幂等跳过）
     * + {@code approval_status=DONE}。<b>双闸幂等</b>（credit 键 + 订单 CAS）绝不双退。不发通知（AB-5B）。
     */
    @Transactional
    public void refundToPawCoin(String refundToken, long userId) {
        RefundRequest r = requireOwned(refundToken, userId);
        if (r.getApprovalStatus() == ApprovalStatus.DONE) {
            return; // 已退，幂等短路
        }
        requireApproved(r);
        ConsultOrder order = requireOrder(r);
        if (order.getPayChannel() != PayChannel.PAWCOIN) {
            throw AppException.conflict("该订单非 PawCoin 支付，不能即时退币");
        }
        // 全额退回 PawCoin（credit 幂等：稳定键，重复确认短路，内部记 FLOAT_LIABILITY ledger）。
        wallet.credit(userId, order.getAmount(), PawCoinTxnType.REFUND,
                "refund_request", r.getId(), "refund-pawcoin-" + refundToken);
        orders.markRefunded(order.getId()); // CAS REFUNDING→REFUNDED（返 0=已退，跳过）
        r.markInstantPawCoinRefunded();
    }

    /**
     * QRIS 订单用户填真钱收款账户（Story 4.5，<b>不可逆边界</b>）。owner + need=APPROVED + 订单渠道=QRIS +
     * approval 未提交 门控。委托 4-3 {@link #fillPayout}（净额后端权威 + PII 加密 + PENDING_APPROVAL，等 4-6）。
     */
    @Transactional
    public void fillPayoutByUser(String refundToken, long userId, PayoutChannel channel,
            String payoutAccount, String accountHolderName) {
        RefundRequest r = requireOwned(refundToken, userId);
        requireApproved(r);
        if (r.getApprovalStatus() != null) {
            throw AppException.conflict("收款账户已提交，不可修改"); // 不可逆边界（UX-DR14）
        }
        ConsultOrder order = requireOrder(r);
        if (order.getPayChannel() != PayChannel.QRIS) {
            throw AppException.conflict("该订单非 QRIS 支付，无需填真钱收款账户");
        }
        fillPayout(refundToken, channel, payoutAccount, accountHolderName);
    }

    // ========== 退款第二段审批闭环（Story 4.6，主管 + 财务）==========

    /**
     * ②主管<b>审批通过</b>（Story 4.6，approver 角色，AB-5E）。职责分离 {@code approver≠submitter}（含 SUPER_ADMIN 不豁免）；
     * 仅 {@code approval_status=PENDING_APPROVAL} 可（重复 409）；{@code approval_note} 必填。{@code →APPROVED}，
     * 订单仍 REFUNDING 等财务打款。不发通知（用户在退款列表见进度）。
     */
    @Transactional
    public void approveRefund(String refundToken, long approverAdminId, String note) {
        if (note == null || note.isBlank()) {
            throw AppException.validation("审批备注必填");
        }
        RefundRequest r = require(refundToken);
        guardDutySeparation(approverAdminId, r.getSubmitterAdminId(), "提交人(客服)", refundToken);
        requirePendingApproval(r);
        r.approveBySupervisor(approverAdminId, note);
        audit.record(approverAdminId, AuditActions.REFUND_APPROVED, "refund_request", refundToken,
                "退款申请审批通过");
    }

    /**
     * ②主管<b>驳回</b>（Story 4.6，A-2 UX 不撒谎）。仅 PENDING_APPROVAL 可；{@code reject_reason} 必填。
     * {@code →REJECTED} + 订单 CAS {@code REFUNDING→COMPLETED} 置 {@code refund_rejected} + 发 {@code REFUND_REJECTED}
     * 通知给用户（REQUIRES_NEW，无 PII）+ 审计。
     */
    @Transactional
    public void rejectRefund(String refundToken, long approverAdminId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("驳回理由必填");
        }
        RefundRequest r = require(refundToken);
        guardDutySeparation(approverAdminId, r.getSubmitterAdminId(), "提交人(客服)", refundToken);
        requirePendingApproval(r);
        r.rejectBySupervisor(approverAdminId, reason);
        orders.markRefundRejectedFromRefunding(r.getOrderId()); // CAS REFUNDING→COMPLETED+refund_rejected
        notifications.send(r.getUserId(), NotificationType.REFUND_REJECTED,
                "退款申请未通过", "你的退款申请未通过审核，如有疑问可在工单中联系客服。",
                NotificationType.REFUND_REJECTED.name(), refundToken);
        audit.record(approverAdminId, AuditActions.REFUND_APPROVAL_REJECTED, "refund_request", refundToken,
                "退款申请主管驳回（订单回落 COMPLETED+refund_rejected，已通知用户）");
    }

    /**
     * ②财务<b>Iris 打款</b>（Story 4.6，payer 角色）。职责分离 {@code payer≠submitter 且 payer≠approver}（含 SUPER_ADMIN 不豁免）；
     * 仅 {@code approval_status=APPROVED} 可（已 DONE 幂等短路）；订单渠道须 QRIS（PawCoin 4-5 已即时退，防御）。
     * {@code →PROCESSING} → 解密 payout PII 调 {@code gateway.disburse}（幂等键）→ 成功：{@code →DONE} + payment_proof +
     * 订单 CAS {@code markRefunded} + {@code REFUND_OUT} 双分录（DEBIT REFUND_OUT / CREDIT CASH_IN，net）+ 审计。
     * <b>三闸幂等</b>：disburse 键 + 订单 CAS + ledger 键。
     */
    @Transactional
    public void payoutRefund(String refundToken, long payerAdminId) {
        RefundRequest r = require(refundToken);
        if (r.getApprovalStatus() == ApprovalStatus.DONE) {
            return; // 已打款，幂等短路
        }
        guardDutySeparation(payerAdminId, r.getSubmitterAdminId(), "提交人(客服)", refundToken);
        guardDutySeparation(payerAdminId, r.getApproverAdminId(), "审批人(主管)", refundToken);
        if (r.getApprovalStatus() != ApprovalStatus.APPROVED) {
            throw AppException.conflict("退款申请未经主管审批通过，不能打款");
        }
        ConsultOrder order = orders.findById(r.getOrderId())
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        if (order.getPayChannel() != PayChannel.QRIS) {
            throw AppException.conflict("非 QRIS 订单不走财务打款（PawCoin 已即时退）");
        }
        r.markProcessing();
        // 解密 payout PII（converter 透明）调 Iris 出款——PII 绝不入日志/审计。幂等键防重复出款。
        DisburseResult res = gateway.disburse(new DisburseRequest(
                refundToken, r.getNetAmount(), "IDR", r.getPayoutChannel().name(),
                r.getPayoutAccount(), r.getAccountHolderName()));
        if (!res.isCompleted()) {
            // 未即时完成（异步/失败）→ 抛出令事务回滚（保持 APPROVED，可重试）。OPEN-5：V1 sandbox 按同步处理。
            throw AppException.serviceUnavailable("退款出款未即时完成，请稍后重试");
        }
        r.completePayout(payerAdminId, res.disbursementRef());
        orders.markRefunded(order.getId()); // CAS REFUNDING→REFUNDED（返 0=已退，跳过）
        // REFUND_OUT 双分录（真钱退款流出）：DEBIT REFUND_OUT net / CREDIT CASH_IN net。借贷平 + 幂等键。
        ledger.post(UUID.randomUUID().toString(), List.of(
                LedgerLine.debit(LedgerAccount.REFUND_OUT, r.getNetAmount(), null, "refund_request", r.getId()),
                LedgerLine.credit(LedgerAccount.CASH_IN, r.getNetAmount(), null, "refund_request", r.getId())),
                "refund-out-" + refundToken);
        audit.record(payerAdminId, AuditActions.REFUND_PAYOUT_RECORDED, "refund_request", refundToken,
                "退款打款完成（Iris ref=" + res.disbursementRef() + "）");
    }

    private void requirePendingApproval(RefundRequest r) {
        if (r.getApprovalStatus() != ApprovalStatus.PENDING_APPROVAL) {
            throw AppException.conflict("退款申请不在待审批态，不可重复处理");
        }
    }

    // ---- 用户端门控守卫 ----

    /** owner 门控：按 token 取，user 不符 → 403（区分 404 不存在 / 403 越权）。 */
    private RefundRequest requireOwned(String refundToken, long userId) {
        RefundRequest r = require(refundToken);
        if (r.getUserId() == null || r.getUserId() != userId) {
            throw AppException.forbidden("无权操作该退款请求");
        }
        return r;
    }

    private void requireApproved(RefundRequest r) {
        if (r.getNeedDecision() != NeedDecision.APPROVED) {
            throw AppException.conflict("退款需求尚未通过客服判定");
        }
    }

    private ConsultOrder requireOrder(RefundRequest r) {
        ConsultOrder order = orders.findById(r.getOrderId())
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        // 订单须已进退款流程（4-4 批准置 REFUNDING）——防越过判定直接退款。
        if (order.getStatus() != ConsultOrderStatus.REFUNDING
                && order.getStatus() != ConsultOrderStatus.REFUNDED) {
            throw AppException.conflict("订单未进入退款流程");
        }
        return order;
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

    /** 客服判定前置：仅 {@code need_decision=PENDING} 可判定（防重复判定，OPEN-1）。 */
    private RefundRequest requirePending(String refundToken) {
        RefundRequest r = require(refundToken);
        if (r.getNeedDecision() != NeedDecision.PENDING) {
            throw AppException.conflict("该退款需求已判定，不可重复处理");
        }
        return r;
    }
}

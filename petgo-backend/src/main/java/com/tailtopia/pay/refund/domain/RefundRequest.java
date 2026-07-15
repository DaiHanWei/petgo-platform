package com.tailtopia.pay.refund.domain;

import com.tailtopia.pay.refund.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 退款请求（Story 4.3，全系统最高危 A-1）。两段审批：①客服判定需求 ②主管审批 + 财务打款。
 * 职责分离（submitter/approver/payer 两两不等）由 {@code RefundService} 服务层强制，含 SUPER_ADMIN 不豁免。
 * payout PII（{@link #payoutAccount}/{@link #accountHolderName}）字段级加密落库，绝不入日志。
 * 实际打款（Midtrans Iris）/ 订单回落 / 通知在 4-4/4-5/4-6。
 */
@Entity
@Table(name = "refund_requests")
public class RefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_token", nullable = false, length = 32, updatable = false)
    private String refundToken;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "related_ticket_id", updatable = false)
    private Long relatedTicketId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    // ① 退款需求判定（客服）
    @Enumerated(EnumType.STRING)
    @Column(name = "need_decision", nullable = false, length = 16)
    private NeedDecision needDecision = NeedDecision.PENDING;

    @Column(name = "submitter_admin_id")
    private Long submitterAdminId;

    // ② 退款申请审批（主管 + 财务）
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", length = 20)
    private ApprovalStatus approvalStatus;

    @Column(name = "approver_admin_id")
    private Long approverAdminId;

    @Column(name = "payer_admin_id")
    private Long payerAdminId;

    // 金额（净额后端权威快照）
    @Column(name = "order_amount", nullable = false, updatable = false)
    private long orderAmount;

    @Column(name = "channel_fee", nullable = false)
    private long channelFee;

    @Column(name = "net_amount", nullable = false)
    private long netAmount;

    // 收款账户（payout_account / account_holder_name 加密密文；payout_channel 枚举非 PII）
    @Enumerated(EnumType.STRING)
    @Column(name = "payout_channel", length = 16)
    private PayoutChannel payoutChannel;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "payout_account", length = 512)
    private String payoutAccount;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "account_holder_name", length = 512)
    private String accountHolderName;

    // 第二段审批留痕（Story 4.6）
    @Column(name = "approval_note", length = 500)
    private String approvalNote;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    /** Iris 出款凭证（disbursementRef，非 PII，可留痕）。 */
    @Column(name = "payment_proof", length = 128)
    private String paymentProof;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RefundRequest() {
    }

    /** 建退款请求：PENDING + 订单金额快照。收款/审批字段后续填。 */
    public static RefundRequest create(long orderId, Long relatedTicketId, long userId,
            String refundToken, long orderAmount) {
        RefundRequest r = new RefundRequest();
        r.orderId = orderId;
        r.relatedTicketId = relatedTicketId;
        r.userId = userId;
        r.refundToken = refundToken;
        r.orderAmount = orderAmount;
        r.needDecision = NeedDecision.PENDING;
        r.channelFee = 0;
        r.netAmount = 0;
        return r;
    }

    /** ①客服判定退款需求（submitter 角色）。 */
    public void markNeedDecision(NeedDecision decision, long submitterAdminId) {
        this.needDecision = decision;
        this.submitterAdminId = submitterAdminId;
    }

    /** 用户选退款方式 + 填收款（4-5 用户行为）：算净额 + 存密文，进 PENDING_APPROVAL。 */
    public void fillPayout(PayoutChannel channel, String payoutAccount, String accountHolderName,
            long channelFee, long netAmount) {
        this.payoutChannel = channel;
        this.payoutAccount = payoutAccount;
        this.accountHolderName = accountHolderName;
        this.channelFee = channelFee;
        this.netAmount = netAmount;
        this.approvalStatus = ApprovalStatus.PENDING_APPROVAL;
    }

    /** ②主管审批通过（approver 角色）。 */
    public void approve(long approverAdminId) {
        this.approvalStatus = ApprovalStatus.APPROVED;
        this.approverAdminId = approverAdminId;
    }

    /** ②财务打款完成（payer 角色）。实际 Midtrans 打款编排在 4-6，本 story 只记角色与终态。 */
    public void recordPayout(long payerAdminId) {
        this.approvalStatus = ApprovalStatus.DONE;
        this.payerAdminId = payerAdminId;
    }

    /**
     * PawCoin 订单即时退币终态（Story 4.5）：<b>系统执行、不经 4-6 主管/财务</b>（PawCoin 站内币无真钱打款风险）。
     * 置 {@code approval_status=DONE}，{@code payer_admin_id} 保持空（非人工打款）。幂等由调用方（credit 幂等键 + 订单 CAS）保证。
     */
    public void markInstantPawCoinRefunded() {
        this.approvalStatus = ApprovalStatus.DONE;
    }

    /** ②主管审批通过（Story 4.6，approver 角色 + 必填备注 + 时间戳）。{@code PENDING_APPROVAL→APPROVED}。 */
    public void approveBySupervisor(long approverAdminId, String note) {
        this.approvalStatus = ApprovalStatus.APPROVED;
        this.approverAdminId = approverAdminId;
        this.approvalNote = note;
        this.approvedAt = Instant.now();
    }

    /** ②主管驳回（Story 4.6，+ 必填理由 + 时间戳）。{@code PENDING_APPROVAL→REJECTED}。订单回落由 service 编排。 */
    public void rejectBySupervisor(long approverAdminId, String reason) {
        this.approvalStatus = ApprovalStatus.REJECTED;
        this.approverAdminId = approverAdminId;
        this.rejectReason = reason;
        this.rejectedAt = Instant.now();
    }

    /** ②财务发起打款（Story 4.6）：{@code APPROVED→PROCESSING}（disburse 调用前置，防重复出款窗口）。 */
    public void markProcessing() {
        this.approvalStatus = ApprovalStatus.PROCESSING;
    }

    /** ②财务打款完成（Story 4.6，payer 角色 + Iris 凭证 + 时间戳）。{@code →DONE}。 */
    public void completePayout(long payerAdminId, String paymentProof) {
        this.approvalStatus = ApprovalStatus.DONE;
        this.payerAdminId = payerAdminId;
        this.paymentProof = paymentProof;
        this.paidAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getRefundToken() {
        return refundToken;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getRelatedTicketId() {
        return relatedTicketId;
    }

    public Long getUserId() {
        return userId;
    }

    public NeedDecision getNeedDecision() {
        return needDecision;
    }

    public Long getSubmitterAdminId() {
        return submitterAdminId;
    }

    public ApprovalStatus getApprovalStatus() {
        return approvalStatus;
    }

    public Long getApproverAdminId() {
        return approverAdminId;
    }

    public Long getPayerAdminId() {
        return payerAdminId;
    }

    public long getOrderAmount() {
        return orderAmount;
    }

    public long getChannelFee() {
        return channelFee;
    }

    public long getNetAmount() {
        return netAmount;
    }

    public PayoutChannel getPayoutChannel() {
        return payoutChannel;
    }

    public String getPayoutAccount() {
        return payoutAccount;
    }

    public String getAccountHolderName() {
        return accountHolderName;
    }

    public String getApprovalNote() {
        return approvalNote;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public String getPaymentProof() {
        return paymentProof;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

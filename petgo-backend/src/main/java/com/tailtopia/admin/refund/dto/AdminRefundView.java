package com.tailtopia.admin.refund.dto;

import java.time.Instant;

/**
 * 后台退款视图（Story 4.6，列表 + 详情共用）。**PII 脱敏**：{@code payoutAccountMasked} 仅末 4 位
 * （详情财务核对用；出款自动读全量密文，UI 绝不显全账号）。户名不回显（PII 红线）。
 *
 * @param refundToken     退款 token
 * @param orderToken      订单 token
 * @param payChannel      订单支付渠道（PAWCOIN/QRIS）
 * @param needDecision    客服判定态
 * @param approvalStatus  第二段审批态（可空）
 * @param orderAmount     订单金额
 * @param netAmount       净额（order − fee）
 * @param payoutChannel   出款渠道（BCA/OVO/GOPAY，可空）
 * @param payoutAccountMasked 收款账号末 4 位（脱敏，可空）
 * @param approvalNote    审批备注（可空）
 * @param rejectReason    驳回理由（可空）
 * @param paymentProof    出款凭证 ref（可空，非 PII）
 * @param sourceTicketToken 来源客服工单 token（可空；主管/财务溯源客服判定依据，bug 20260728-384）
 */
public record AdminRefundView(
        String refundToken,
        String orderToken,
        String payChannel,
        String needDecision,
        String approvalStatus,
        long orderAmount,
        long netAmount,
        String payoutChannel,
        String payoutAccountMasked,
        String approvalNote,
        String rejectReason,
        String paymentProof,
        String sourceTicketToken,
        String stage,
        long channelFee,
        String submitterName,
        String approverName,
        String payerName,
        Instant createdAt,
        Instant approvedAt,
        Instant rejectedAt,
        Instant paidAt,
        String payoutProofKey) {

    /** V1.3.0 Story 2.8：三段流阶段（submit / approve / payout / closed），由 need_decision + approval_status 派生。 */
    public static String stageOf(String needDecision, String approvalStatus) {
        if ("REJECTED".equals(needDecision) || "REJECTED".equals(approvalStatus) || "DONE".equals(approvalStatus)) {
            return "closed";
        }
        if ("PENDING".equals(needDecision) || needDecision == null) {
            return "submit";
        }
        if (approvalStatus == null || "PENDING_APPROVAL".equals(approvalStatus)) {
            return "approve";
        }
        return "payout";
    }

    public boolean terminal() {
        return "closed".equals(stage);
    }

    /** 已驳回（客服或主管）。 */
    public boolean rejected() {
        return "REJECTED".equals(needDecision) || "REJECTED".equals(approvalStatus);
    }

    /** 用户已填收款方式（净额才有意义：填收款前 net_amount 为 0）。 */
    public boolean payoutFilled() {
        return payoutChannel != null;
    }

    /** 净额 ≠ 订单金额（渠道手续费）→ 黄底差额提示；填收款前不提示。 */
    public boolean netDiffers() {
        return payoutFilled() && channelFee > 0;
    }
}

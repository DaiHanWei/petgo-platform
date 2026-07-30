package com.tailtopia.admin.refund.dto;

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
        String paymentProof) {
}

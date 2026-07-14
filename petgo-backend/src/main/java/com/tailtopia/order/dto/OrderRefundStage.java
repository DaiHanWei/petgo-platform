package com.tailtopia.order.dto;

/**
 * 退款子阶段（Story 5.3，兽医订单「6 态」的退款子变体）。由 refund_requests 派生，供详情展示退款进度。
 *
 * <ul>
 *   <li>{@link #AWAITING_METHOD}：待用户选退款方式/填收款（approval_status 空，4-5）。</li>
 *   <li>{@link #AWAITING_APPROVAL}：待主管审批（PENDING_APPROVAL，4-6）。</li>
 *   <li>{@link #AWAITING_PAYOUT}：待财务打款（APPROVED，4-6）。</li>
 *   <li>{@link #PROCESSING}：打款处理中（PROCESSING，4-6）。</li>
 *   <li>{@link #REJECTED}：退款申请未通过（订单 COMPLETED+refund_rejected，A-2）。</li>
 * </ul>
 */
public enum OrderRefundStage {
    AWAITING_METHOD,
    AWAITING_APPROVAL,
    AWAITING_PAYOUT,
    PROCESSING,
    REJECTED
}

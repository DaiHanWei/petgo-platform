package com.tailtopia.pay.refund.domain;

/**
 * 第二段「退款申请审批」状态（Story 4.3，主管+财务 AB-5E，落库 varchar UPPER_SNAKE）。
 * PENDING_APPROVAL（用户填完收款）→ APPROVED（主管）→ PROCESSING（财务发起打款）→ DONE（打款完成）；REJECTED 主管驳回。
 * 完整流转编排（含实际 Midtrans Iris 打款）在 4-6；本 story 只定义 + 主管/财务角色分配原语。
 */
public enum ApprovalStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    PROCESSING,
    DONE
}

package com.tailtopia.pay.refund.domain;

/**
 * 第一段「退款需求判定」状态（Story 4.3，客服 AB-5B，落库 varchar UPPER_SNAKE）。
 * APPROVED → 解锁用户选退款方式（不发通知）；REJECTED → 订单回落 COMPLETED+refund_rejected + 通知（行为在 4-4）。
 */
public enum NeedDecision {
    PENDING,
    APPROVED,
    REJECTED
}

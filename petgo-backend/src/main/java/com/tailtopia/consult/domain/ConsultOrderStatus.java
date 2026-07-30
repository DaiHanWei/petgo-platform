package com.tailtopia.consult.domain;

/**
 * 兽医咨询订单状态（Story 3.1，支付成功才建的持久订单）。落库 varchar UPPER_SNAKE。
 *
 * <p>{@code IN_PROGRESS}（会话进行中）→ {@code COMPLETED}（结束）→ {@code REFUNDING}（退款中）→ {@code REFUNDED}。
 * <b>无 CANCELLED</b>——未扣费根本不建单（A-5）；取消/超时留在 {@code consult_requests} 层（删行）。
 */
public enum ConsultOrderStatus {
    IN_PROGRESS,
    COMPLETED,
    REFUNDING,
    REFUNDED
}

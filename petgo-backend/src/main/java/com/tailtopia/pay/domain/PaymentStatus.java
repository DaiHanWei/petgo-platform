package com.tailtopia.pay.domain;

/**
 * 支付意图状态（Story 1.1，落库 varchar(16) + CHECK，UPPER_SNAKE）。
 *
 * <p>状态机：{@code PENDING →(回调/轮询到账) PAID}、{@code PENDING →(拒付/取消) FAILED}、
 * {@code PENDING →(超时) EXPIRED}。终态（PAID/FAILED/EXPIRED）不可再迁移——回调双通道重放由
 * 「已终态即幂等返回」+ {@code gateway_ref} 唯一约束兜底（本 story 只推进一次）。
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    EXPIRED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}

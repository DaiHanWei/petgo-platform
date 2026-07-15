package com.tailtopia.consult.domain;

/**
 * 兽医咨询订单节点事件类型（Story 3.1，{@code consult_order_stage_events} append-only 历史）。
 * 落库 varchar UPPER_SNAKE。每个节点一行 INSERT，绝不覆盖旧行（对账/审计留痕）。
 */
public enum ConsultStageEvent {
    ACCEPTED,
    PAID,
    SESSION_STARTED,
    SESSION_ENDED,
    REFUND_REQUESTED,
    REFUND_COMPLETED,
    REFUND_REJECTED
}

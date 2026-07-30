package com.tailtopia.admin.consult.dto;

import java.time.Instant;

/** 订单阶段事件行（Story 9.3，append-only 时间线）。 */
public record AdminConsultOrderStageRow(String eventType, Instant occurredAt, String note) {
}

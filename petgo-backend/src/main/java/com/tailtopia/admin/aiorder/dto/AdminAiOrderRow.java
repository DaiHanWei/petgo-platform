package com.tailtopia.admin.aiorder.dto;

import java.time.Instant;

/** AI 问诊订单列表行（Story 9.4，只读）。 */
public record AdminAiOrderRow(
        String orderToken,
        String displayNo,
        long userId,
        long triageTaskId,
        long amount,
        String payChannel,
        String status,
        Instant paidAt,
        Instant createdAt) {
}

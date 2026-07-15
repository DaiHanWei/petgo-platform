package com.tailtopia.admin.aiorder.dto;

import java.time.Instant;

/** AI 问诊订单详情（Story 9.4，只读）。无退款/分成/待核查。 */
public record AdminAiOrderDetail(
        String orderToken,
        long userId,
        long triageTaskId,
        long amount,
        String payChannel,
        String paymentIntentToken,
        String status,
        Instant paidAt,
        Instant createdAt) {
}

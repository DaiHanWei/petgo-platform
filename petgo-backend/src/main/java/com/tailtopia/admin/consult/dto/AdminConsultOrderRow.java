package com.tailtopia.admin.consult.dto;

import java.time.Instant;

/** 兽医咨询订单列表行（Story 9.3，只读）。 */
public record AdminConsultOrderRow(
        String orderToken,
        long userId,
        long vetId,
        long amount,
        Long vetPayout,
        String statusCode,
        int rebroadcastCount,
        String verifyStatus,
        Instant paidAt,
        Instant createdAt) {
}

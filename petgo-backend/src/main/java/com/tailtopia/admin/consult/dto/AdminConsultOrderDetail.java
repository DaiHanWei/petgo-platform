package com.tailtopia.admin.consult.dto;

import java.time.Instant;
import java.util.List;

/** 兽医咨询订单详情（Story 9.3，只读 + 待核查标记态 + 阶段时间线）。无退款入口。 */
public record AdminConsultOrderDetail(
        String orderToken,
        long userId,
        long vetId,
        long petProfileId,
        long amount,
        String payChannel,
        Long vetPayout,
        Integer vetShareRateSnapshot,
        Long unitPriceSnapshot,
        String statusCode,
        boolean refundRejected,
        int rebroadcastCount,
        String verifyStatus,
        String verifyNote,
        Instant sessionStartedAt,
        Instant sessionEndedAt,
        Instant paidAt,
        Instant createdAt,
        List<AdminConsultOrderStageRow> stages) {
}

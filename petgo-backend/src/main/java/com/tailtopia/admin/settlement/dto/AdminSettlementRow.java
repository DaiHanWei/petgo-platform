package com.tailtopia.admin.settlement.dto;

import java.time.Instant;

/** 兽医月结对账行（Story 9.5，AB-8D）。 */
public record AdminSettlementRow(
        long id,
        long vetId,
        String period,
        int orderCount,
        long grossAmount,
        long payoutAmount,
        String status,
        String paymentProof,
        Instant paidAt,
        Instant generatedAt) {
}

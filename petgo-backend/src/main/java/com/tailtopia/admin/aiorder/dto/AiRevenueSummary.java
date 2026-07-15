package com.tailtopia.admin.aiorder.dto;

/** AI 问诊收入汇总（Story 9.4，AB-8C）。收入 = COMPLETED 金额之和；PENDING/ABNORMAL 不计收入。 */
public record AiRevenueSummary(
        long totalRevenue,
        long completedCount,
        long pendingCount,
        long abnormalCount,
        long revenueQris,
        long revenuePawcoin) {
}

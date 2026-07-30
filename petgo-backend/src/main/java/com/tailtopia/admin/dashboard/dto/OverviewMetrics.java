package com.tailtopia.admin.dashboard.dto;

/** 运营概览四模块指标（Story 9.10，AB-1.1-01，只读聚合）。 */
public record OverviewMetrics(
        // 用户
        long totalUsers,
        long virtualAccounts,
        // 订单
        long vetConsultOrders,
        long aiCompletedOrders,
        long aiRevenue,
        // 内容
        long posts,
        long comments,
        // 兽医
        long totalVets,
        long pendingSettlements) {
}

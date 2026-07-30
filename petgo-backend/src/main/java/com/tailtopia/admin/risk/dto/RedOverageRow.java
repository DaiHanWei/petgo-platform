package com.tailtopia.admin.risk.dto;

/** 红色超额监控行（Story 9.6）：用户 RED 计数 + 复核态（TO_VERIFY/RESOLVED/空）。 */
public record RedOverageRow(long userId, long redCount, String reviewStatus, String note) {
}

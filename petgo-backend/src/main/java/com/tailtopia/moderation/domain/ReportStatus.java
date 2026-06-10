package com.tailtopia.moderation.domain;

/**
 * 举报工单状态（Story 3.7，落库 varchar UPPER_SNAKE）。仅 ADMIN 人工流转，无自动下架。
 */
public enum ReportStatus {
    PENDING,    // 待处理
    RESOLVED,   // 已下架处理
    DISMISSED   // 已驳回
}

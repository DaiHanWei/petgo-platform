package com.tailtopia.admin.anomaly.domain;

/**
 * 异常工单状态（Story 5.1，落库 varchar UPPER_SNAKE）。归档＝置 {@link #RESOLVED}（工单不可删，AC6）。
 */
public enum AnomalyStatus {
    OPEN,
    RESOLVED
}

package com.tailtopia.admin.anomaly.domain;

/**
 * 问诊异常工单类型（Story 5.1，AB-4A，落库 varchar UPPER_SNAKE）。
 * V1.0.0 唯一触发源 {@link #VET_BANNED}（兽医被封禁强制中断进行中会话）。
 */
public enum AnomalyType {
    VET_BANNED
}

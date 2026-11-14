package com.tailtopia.profile.domain;

/**
 * 驱虫类型（V1.5.0 健康记录提醒 Story 1.1，PRD FR-160）。落库 {@code health_records.deworm_type} varchar(16) + CHECK。
 * {@code null} = 存量未分型（Legacy）。{@link #HEARTWORM} 只对 DOG 合法。
 */
public enum DewormType {
    INTERNAL,
    EXTERNAL,
    FLEA_WORM,
    HEARTWORM
}

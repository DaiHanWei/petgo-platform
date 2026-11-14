package com.tailtopia.profile.domain;

/**
 * 结构化健康记录类型（Story 7.1，FR-45A）。落库 varchar(16) + CHECK。UPPER_SNAKE。
 * 展示串本地化在前端（App 绝不渲染后端显示串，按 code 本地化）。
 */
public enum HealthRecordType {
    VACCINE,
    DEWORM,
    MENSTRUATION,
    NEUTER,
    CUSTOM,
    /** Kontrol dokter（V1.5.0 健康记录提醒 Story 1.1，FR-161）。只有 eventDate / note / nextDate 有意义；旧版客户端写入一律 400。 */
    VET_VISIT
}

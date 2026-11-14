package com.tailtopia.profile.dto;

import java.time.LocalDate;

/**
 * 健康记录表单预填请求（{@code POST /api/v1/pet-profiles/me/health-records/preview}，V1.5.0 Story 1.2，架构增量 §6.1）。
 * 只读：不落库。{@code recordId} = 编辑中的记录（历史计算排除它自己），新建为 null。
 */
public record HealthRecordPreviewRequest(Long recordId, String type, String vaccinePreset, String vaccineName,
        Integer dose, String dewormType, Integer productDurationMonths, LocalDate eventDate) {
}

package com.tailtopia.profile.dto;

import com.tailtopia.profile.domain.HealthRecord;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 健康记录响应（Story 7.1）。{@code editable=true} 恒真——结构化记录用户可编辑（7-2 与问诊存档只读条目
 * 混排时按 {@code editable} 区分可点）。Jackson NON_NULL：null 字段省略。
 */
public record HealthRecordResponse(
        long id,
        String type,
        String customName,
        String vaccineName,
        LocalDate eventDate,
        String note,
        boolean editable,
        Instant createdAt,
        // ===== V1.5.0 健康记录提醒 Story 1.1 =====
        String vaccinePreset,
        Integer dose,
        String dewormType,
        Integer productDurationMonths,
        LocalDate nextDate) {

    public static HealthRecordResponse from(HealthRecord r) {
        return new HealthRecordResponse(
                r.getId(),
                r.getType().name(),
                r.getCustomName(),
                r.getVaccineName(),
                r.getEventDate(),
                r.getNote(),
                true,
                r.getCreatedAt(),
                r.getVaccinePreset() == null ? null : r.getVaccinePreset().name(),
                r.getDose(),
                r.getDewormType() == null ? null : r.getDewormType().name(),
                r.getProductDurationMonths(),
                r.getNextDate());
    }
}

package com.tailtopia.profile.dto;

import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.health.RecordDisplayStatus;
import com.tailtopia.profile.health.RecordStatus;
import java.time.LocalDate;

/**
 * 健康时间线混排项（Story 7.2，FR-45B）。两源合并：结构化健康记录（{@code kind=RECORD, editable=true}）
 * + 问诊存档（{@code kind=CONSULT, editable=false} 只读，来自 FR-16）。前端按 {@code editable} 区分可点。
 * 问诊条目**不入 health_records 表**（运行时合并）。Jackson NON_NULL：null 字段省略。
 */
public record HealthListItemResponse(
        String kind,
        long id,
        boolean editable,
        String type,
        String customName,
        String vaccineName,
        String note,
        String symptomSummary,
        String aiLevel,
        LocalDate eventDate,
        // ===== V1.5.0 健康记录提醒 Story 1.1（架构增量 §6.1）：存储原值，编辑表单用 =====
        String vaccinePreset,
        Integer dose,
        String dewormType,
        Integer productDurationMonths,
        LocalDate nextDate,
        // ===== V1.5.0 Story 1.2（架构增量 §3.4）：服务端算好的显示字段，App 只按 recordStatus 选样式 =====
        LocalDate displayNextDate,
        String recordStatus,
        boolean nextDateLocked) {

    public static HealthListItemResponse ofRecord(HealthRecord r, RecordDisplayStatus.Display display) {
        return new HealthListItemResponse(
                "RECORD", r.getId(), true, r.getType().name(),
                r.getCustomName(), r.getVaccineName(), r.getNote(),
                null, null, r.getEventDate(),
                r.getVaccinePreset() == null ? null : r.getVaccinePreset().name(),
                r.getDose(),
                r.getDewormType() == null ? null : r.getDewormType().name(),
                r.getProductDurationMonths(),
                r.getNextDate(),
                display == null ? null : display.displayNextDate(),
                display == null ? null : display.recordStatus().name(),
                display != null && display.nextDateLocked());
    }

    public static HealthListItemResponse ofConsult(HealthEvent e) {
        return new HealthListItemResponse(
                "CONSULT", e.getId(), false, "CONSULT",
                null, null, null,
                e.getSymptomSummary(), e.getAiLevel(), e.getEventDate(),
                null, null, null, null, null,
                null, RecordStatus.DONE.name(), false);
    }
}

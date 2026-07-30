package com.tailtopia.profile.dto;

import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.HealthRecord;
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
        LocalDate eventDate) {

    public static HealthListItemResponse ofRecord(HealthRecord r) {
        return new HealthListItemResponse(
                "RECORD", r.getId(), true, r.getType().name(),
                r.getCustomName(), r.getVaccineName(), r.getNote(),
                null, null, r.getEventDate());
    }

    public static HealthListItemResponse ofConsult(HealthEvent e) {
        return new HealthListItemResponse(
                "CONSULT", e.getId(), false, "CONSULT",
                null, null, null,
                e.getSymptomSummary(), e.getAiLevel(), e.getEventDate());
    }
}

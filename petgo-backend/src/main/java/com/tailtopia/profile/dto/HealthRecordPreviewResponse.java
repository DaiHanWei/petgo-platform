package com.tailtopia.profile.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 健康记录表单预填结果（V1.5.0 Story 1.2，架构增量 §6.1）。<b>规则只有这一份</b>：App 只显示，不复制任何日期规则。
 *
 * @param suggestedNextDate 预填 Next date（无 → null）
 * @param nextDateAllowed   该类型 / 宠物是否有 Next date 字段
 * @param nextDateLocked    发情周期后续记录：只读显示 {@code lockedNextDate}
 * @param nextDateMin       可选最早日 = eventDate + 1 天（eventDate 空 → null）
 * @param nextDateMax       可选最晚日 = eventDate + 3 年
 * @param dueVaccinePreset  到期疫苗（DP → Combo 第 1 剂）
 * @param dueDose           到期剂次
 * @param hints             表单提示 code，顺序固定（{@code PreviewHint} 声明顺序）
 */
public record HealthRecordPreviewResponse(LocalDate suggestedNextDate, boolean nextDateAllowed,
        boolean nextDateLocked, LocalDate lockedNextDate, LocalDate nextDateMin, LocalDate nextDateMax,
        String dueVaccinePreset, Integer dueDose, List<String> hints) {
}

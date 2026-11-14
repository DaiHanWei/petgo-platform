package com.tailtopia.profile.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 部分更新健康记录请求（{@code PATCH /api/v1/pet-profiles/me/health-records/{id}}，Story 7.1）。
 * 全字段可选：非 null 才更新。{@code type} 非 null 时解析校验（CUSTOM 须 customName，service 兜底）。
 */
public record HealthRecordUpdateRequest(
        String type,
        @Size(max = 20, message = "自定义名称不能超过 20 字") String customName,
        @Size(max = 30, message = "疫苗名称不能超过 30 字") String vaccineName,
        @PastOrPresent(message = "日期不能是未来") LocalDate eventDate,
        @Size(max = 100, message = "备注不能超过 100 字") String note,
        // ===== V1.5.0 健康记录提醒 Story 1.1（架构增量 §6.1）：只有 1.5.0 客户端的请求才读这些字段 =====
        String vaccinePreset,
        @Min(value = 1, message = "剂次须在 1–5 之间") @Max(value = 5, message = "剂次须在 1–5 之间") Integer dose,
        String dewormType,
        Integer productDurationMonths,
        /** 下次日期；null 且 clearNextDate≠true = 不改。 */
        LocalDate nextDate,
        /** true = 显式清空 Next date。 */
        Boolean clearNextDate) {

    /** 兼容构造（V1.5.0 之前的 5 字段形状；现有调用方 / 测试不改）。 */
    public HealthRecordUpdateRequest(String type, String customName, String vaccineName, LocalDate eventDate,
            String note) {
        this(type, customName, vaccineName, eventDate, note, null, null, null, null, null, null);
    }
}

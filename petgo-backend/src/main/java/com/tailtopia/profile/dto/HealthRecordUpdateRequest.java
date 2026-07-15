package com.tailtopia.profile.dto;

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
        @Size(max = 100, message = "备注不能超过 100 字") String note) {
}

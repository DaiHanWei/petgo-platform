package com.tailtopia.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 创建健康记录请求（{@code POST /api/v1/pet-profiles/me/health-records}，Story 7.1）。服务端校验权威。
 * {@code ownerId} 绝不在此 DTO——取自 JWT。{@code type} 为枚举字符串（VACCINE/DEWORM/MENSTRUATION/NEUTER/CUSTOM），
 * 服务端解析校验；CUSTOM 须 {@code customName}（service 兜底）。{@code eventDate} 不可未来（无下限）。
 */
public record HealthRecordCreateRequest(
        @NotBlank(message = "记录类型必选") String type,
        @Size(max = 20, message = "自定义名称不能超过 20 字") String customName,
        @Size(max = 30, message = "疫苗名称不能超过 30 字") String vaccineName,
        @NotNull(message = "日期必填") @PastOrPresent(message = "日期不能是未来") LocalDate eventDate,
        @Size(max = 100, message = "备注不能超过 100 字") String note) {
}

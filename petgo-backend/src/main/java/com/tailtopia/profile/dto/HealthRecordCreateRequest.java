package com.tailtopia.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
        @Size(max = 100, message = "备注不能超过 100 字") String note,
        // ===== V1.5.0 健康记录提醒 Story 1.1（架构增量 §6.1）：只有 1.5.0 客户端的请求才读这些字段 =====
        /** VaccinePreset 名（F3/F4/RABIES/…/OTHER），可空。 */
        String vaccinePreset,
        @Min(value = 1, message = "剂次须在 1–5 之间") @Max(value = 5, message = "剂次须在 1–5 之间") Integer dose,
        /** DewormType 名（INTERNAL/EXTERNAL/FLEA_WORM/HEARTWORM）；1.5.0 客户端的 DEWORM 必填。 */
        String dewormType,
        /** 产品时长（月）：只允许 1 / 3 / 8，只在 EXTERNAL 落库。 */
        Integer productDurationMonths,
        /** 下次日期；须 eventDate &lt; nextDate ≤ eventDate + 3 年。 */
        LocalDate nextDate,
        /** true = 显式清空 Next date（仿 {@code PetProfileUpdateRequest.clearWeight} 语义）。 */
        Boolean clearNextDate) {

    /** 兼容构造（V1.5.0 之前的 5 字段形状；现有调用方 / 测试不改）。 */
    public HealthRecordCreateRequest(String type, String customName, String vaccineName, LocalDate eventDate,
            String note) {
        this(type, customName, vaccineName, eventDate, note, null, null, null, null, null, null);
    }
}

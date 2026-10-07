package com.tailtopia.tailsonality.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 佩戴切换请求（V1.3.2 Story 3.3 · {@code PUT …/tailsonality/badge}）：结果 token。 */
public record BadgeEquipRequest(@NotBlank @Size(max = 32) String resultToken) {
}

package com.tailtopia.tailsonality.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 主人类型写入（Story 2.5）：大写四字母，**不接受**带后缀（{@code INFP-H}）、小写、别名 → 422。 */
public record OwnerTypeRequest(@NotNull @Pattern(regexp = "^[EI][NS][TF][JP]$") String typeCode) {
}

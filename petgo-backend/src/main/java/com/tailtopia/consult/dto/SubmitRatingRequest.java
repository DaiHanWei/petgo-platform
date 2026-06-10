package com.tailtopia.consult.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交评分请求（Story 5.6，FR-33）。1-5 星必填 + ≤100 字选填。服务端权威校验。
 */
public record SubmitRatingRequest(
        @NotNull @Min(1) @Max(5) Integer stars,
        @Size(max = 100, message = "评价不能超过 100 字") String comment) {
}

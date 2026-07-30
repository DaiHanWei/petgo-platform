package com.tailtopia.support.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 用户提交 CSAT 请求（Story 4.7）。{@code score} 1-5（双闸：Bean 校验 + V70 DB CHECK）；
 * {@code comment} ≤100 可空（V70 VARCHAR(100)）。
 *
 * @param score   满意度 1-5
 * @param comment 评论（可空，≤100）
 */
public record SubmitCsatRequest(
        @Min(value = 1, message = "评分需 1-5") @Max(value = 5, message = "评分需 1-5") int score,
        @Size(max = 100, message = "评论过长") String comment) {
}

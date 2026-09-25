package com.tailtopia.onboarding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 置位一个引导标记（V1.3.0 批次 A · Story 5.4）。
 *
 * <p>🔴 {@code key} **必须是已登记的键**（见 {@code OnboardingMarkKey}）——
 * 开放任意字符串等于让客户端往表里写它想写的任何东西。
 */
public record MarkOnboardingRequest(@NotBlank @Size(max = 64) String key) {
}

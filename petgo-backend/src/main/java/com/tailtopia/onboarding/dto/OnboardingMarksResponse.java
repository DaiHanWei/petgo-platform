package com.tailtopia.onboarding.dto;

import java.util.List;

/**
 * 当前用户已置位的引导标记（V1.3.0 批次 A · Story 5.4）。
 *
 * <p>只回键名列表 —— 客户端要问的问题只有「这个引导我弹过没有」。
 */
public record OnboardingMarksResponse(List<String> marks) {
}

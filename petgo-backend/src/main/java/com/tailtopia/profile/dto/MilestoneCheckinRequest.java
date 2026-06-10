package com.petgo.profile.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 里程碑用户打卡请求（Story 8.4 · FR-42「已打卡」）。关联一条本人成长日历内容到该里程碑。
 *
 * @param contentId 成长日历内容 id（必填）
 */
public record MilestoneCheckinRequest(@NotNull Long contentId) {
}

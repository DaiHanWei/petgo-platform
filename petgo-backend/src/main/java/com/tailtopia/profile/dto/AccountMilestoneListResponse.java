package com.tailtopia.profile.dto;

import java.util.List;

/**
 * {@code GET /api/v1/me/account-milestones}（V1.5.0 onboarding-recall Story 2.3 · OR-10）。
 *
 * @param items          固定 3 项（Teman pertama / Simpan pertama / Lulus Pemula）
 * @param completedCount 已完成项数
 * @param totalCount     恒 3
 */
public record AccountMilestoneListResponse(List<AccountMilestoneItemResponse> items, int completedCount,
        int totalCount) {
}

package com.tailtopia.profile.dto;

import java.util.List;

/**
 * 里程碑列表页响应（Story 8.1，FR-42）。顶部宠物信息 + 总进度 + L/M/S 三级分区。
 * Jackson NON_NULL：{@code petAvatarUrl} 可省略。响应**不含任何自增 DB id**（对外用里程碑 code）。
 *
 * @param petName        宠物名
 * @param petAvatarUrl   宠物头像（可空 → 省略）
 * @param completedCount 总已完成数
 * @param totalCount     总数（猫/狗 30，其他 15）
 * @param groups         分级分组，顺序 L → M → S
 */
public record MilestoneListResponse(
        String petName,
        String petAvatarUrl,
        int completedCount,
        int totalCount,
        List<MilestoneGroupResponse> groups) {
}

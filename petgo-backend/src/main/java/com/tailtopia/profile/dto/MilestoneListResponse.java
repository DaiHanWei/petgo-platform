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
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record MilestoneListResponse(
        String petName,
        String petAvatarUrl,
        int completedCount,
        int totalCount,
        List<MilestoneGroupResponse> groups,
        MilestoneGroupResponse accountGroup) {

    /**
     * 旧形状（无账号成就区）。V1.5.0 onboarding-recall Story 2.3（OR-11）：≥ 1.5.0 客户端另带 {@code accountGroup}
     * （{@code level = "ACCOUNT"}，3 项），且 S 组与计数都不含它们；&lt; 1.5.0 为 null（不序列化，形状逐字不变）。
     */
    public MilestoneListResponse(String petName, String petAvatarUrl, int completedCount, int totalCount,
            List<MilestoneGroupResponse> groups) {
        this(petName, petAvatarUrl, completedCount, totalCount, groups, null);
    }
}

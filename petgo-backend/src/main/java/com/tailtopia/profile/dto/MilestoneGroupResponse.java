package com.tailtopia.profile.dto;

import java.util.List;

/**
 * 里程碑分级分组响应（Story 8.1，FR-42 列表页按 L/M/S 三级独立分区）。
 *
 * @param level          级别 S/M/L
 * @param completedCount 该级已完成数
 * @param totalCount     该级总数
 * @param items          该级里程碑项（按 sortOrder 升序）
 */
public record MilestoneGroupResponse(
        String level,
        int completedCount,
        int totalCount,
        List<MilestoneItemResponse> items) {
}

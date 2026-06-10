package com.petgo.profile.dto;

import java.time.Instant;

/**
 * 里程碑单项响应（Story 8.1，FR-42）。对外标识用 {@code code}（非顺序 id）。
 * Jackson NON_NULL：未完成时 {@code completedAt} 省略。
 *
 * @param code        目录码（C-S1 等）
 * @param title       中文标题
 * @param level       级别 S/M/L
 * @param triggerType SYSTEM_AUTO/USER_CHECKIN/PUSH_PUBLISH（决定 8.2 点击交互）
 * @param completed   是否已完成
 * @param completedAt 完成时间（未完成为 null → 省略）
 */
public record MilestoneItemResponse(
        String code,
        String title,
        String level,
        String triggerType,
        boolean completed,
        Instant completedAt) {
}

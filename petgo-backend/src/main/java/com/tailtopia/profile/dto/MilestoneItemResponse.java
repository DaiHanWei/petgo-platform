package com.tailtopia.profile.dto;

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
 * @param celebratedAt 庆祝页展示过的时刻（V1.3.0 Story 1.5 · FR-111 · AD-A1.3）。
 *        <p><b>「已完成且未庆祝」= {@code completed == true} 且本字段为 null</b> ——
 *        这是补庆祝的**全链路唯一判据**，客户端不得另立本地标记。
 *        <p>未完成时恒为 null（没完成过自然谈不上庆祝），Jackson NON_NULL 下直接省略；
 *        已完成但未庆祝时**同样省略** —— 客户端按「字段缺失 = 未庆祝」判，与 completed 合读。
 */
public record MilestoneItemResponse(
        String code,
        String title,
        String level,
        String triggerType,
        boolean completed,
        Instant completedAt,
        Instant celebratedAt) {
}

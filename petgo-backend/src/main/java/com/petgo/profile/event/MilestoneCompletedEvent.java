package com.petgo.profile.event;

import com.petgo.profile.domain.MilestoneLevel;

/**
 * 里程碑完成领域事件（Story 8.6，过去式）。每次**新**完成（自动 / 打卡 / 计数 / 组合）后由
 * {@code MilestoneCompletionService} 发布。供 notify 模块订阅：**L 级**达成 → 经 6.1
 * {@code NotificationService} 下发 {@code MILESTONE_NODE} 通知（达成推送 + 通知中心 6.6 真数据）。
 *
 * <p>content/consult 既有范式：profile 不直调 notify，经事件解耦。S/M 级也发本事件（notify 侧仅对 L 级动作）。
 *
 * @param ownerId 档案所有者 user id
 * @param code    里程碑目录码（C-L1 等）
 * @param level   级别（notify 仅对 L 级下发达成推送）
 * @param title   里程碑中文标题（推送文案用）
 */
public record MilestoneCompletedEvent(long ownerId, String code, MilestoneLevel level, String title) {
}

package com.tailtopia.profile.dto;

import java.time.Instant;

/**
 * 账号级里程碑的一项（V1.5.0 onboarding-recall Story 2.3 · OR-10）。
 *
 * @param flagKey            {@code FIRST_FOLLOW_USER / FIRST_COLLECT_CONTENT / LULUS_PEMULA}
 * @param code               账号默认宠物物种的编码（无宠物取 CAT 编码；App 按 code 本地化标题 / 徽章）
 * @param completed          账号是否已有该 flag
 * @param completedAt        flag 的 {@code occurred_at}（UTC；未完成为 null）
 * @param pendingCelebration 只对 Lulus Pemula 可能为 true：flag 存在且 {@code celebrated_at IS NULL}（待补弹）
 */
public record AccountMilestoneItemResponse(
        String flagKey,
        String code,
        boolean completed,
        Instant completedAt,
        boolean pendingCelebration) {
}

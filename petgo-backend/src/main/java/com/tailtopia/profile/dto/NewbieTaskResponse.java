package com.tailtopia.profile.dto;

import java.util.List;

/**
 * 新手任务进度响应（Story 7.3 · {@code GET /api/v1/me/newbie-tasks}）。
 *
 * <p>PII 护栏：只回布尔完成态与计数，绝不回传健康记录明文。前端按 {@code items[].key} 本地化标签。
 *
 * @param items                 6 任务各自完成态（固定顺序）
 * @param completedCount        已完成任务数
 * @param total                 任务总数（恒 6）
 * @param lulusPemulaUnlocked   聚合里程碑 Lulus Pemula 是否已解锁
 */
public record NewbieTaskResponse(
        List<Item> items,
        int completedCount,
        int total,
        boolean lulusPemulaUnlocked) {

    /**
     * 单个新手任务。
     *
     * @param key  稳定对外标识（{@link com.tailtopia.profile.domain.NewbieTask} 名）
     * @param done 是否完成
     */
    public record Item(String key, boolean done) {
    }
}

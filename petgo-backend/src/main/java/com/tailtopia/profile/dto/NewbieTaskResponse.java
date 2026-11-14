package com.tailtopia.profile.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 新手任务进度响应（{@code GET /api/v1/me/newbie-tasks}）。
 *
 * <p>PII 护栏：只回布尔完成态与计数，绝不回传健康记录 / 评论明文。前端按 {@code items[].key} 本地化标签。
 *
 * <p>V1.5.0 onboarding-recall Story 2.1（FR-176 · OR-1 / OR-9）：≥ 1.5.0 客户端拿 7 项 + 末尾三个新字段；
 * &lt; 1.5.0 客户端（{@code LegacyNewbieTaskCompat}）拿旧 6 项、新字段为 null —— {@code NON_NULL} 让旧版响应
 * 逐字保持原形状（旧包遇到未知字段虽无害，但不多给）。
 *
 * @param items                 各任务完成态（固定顺序）
 * @param completedCount        已完成任务数
 * @param total                 任务总数（新版 7 / 旧版 6）
 * @param lulusPemulaUnlocked   Lulus Pemula 是否已解锁
 * @param hasPet                账号下有 ACTIVE 或只读宠物（新版；旧版 null）
 * @param oneWeekDaysLeft       ⑦ 未完成时剩余天数（≥ 1），完成为 0（新版；旧版 null）
 * @param replyGoPostId         ⑥ 未完成且有人评论过我的帖子 → 最近一条被评论的帖子 id；否则 null（省略）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NewbieTaskResponse(
        List<Item> items,
        int completedCount,
        int total,
        boolean lulusPemulaUnlocked,
        Boolean hasPet,
        Integer oneWeekDaysLeft,
        Long replyGoPostId) {

    /** 旧形状（&lt; 1.5.0 客户端，{@code LegacyNewbieTaskCompat}）：新字段全 null。 */
    public NewbieTaskResponse(List<Item> items, int completedCount, int total, boolean lulusPemulaUnlocked) {
        this(items, completedCount, total, lulusPemulaUnlocked, null, null, null);
    }

    /**
     * 单个新手任务。
     *
     * @param key  稳定对外标识（新版为 {@link com.tailtopia.profile.domain.NewbieTask} 名）
     * @param done 是否完成
     */
    public record Item(String key, boolean done) {
    }
}

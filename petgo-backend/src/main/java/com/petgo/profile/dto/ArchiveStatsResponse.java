package com.petgo.profile.dto;

/**
 * 成长档案统计栏（Story 2.4 AC5）。「快乐时刻 X 条 · 问诊 X 次」。
 *
 * <p>里程碑总数 {@code milestoneTotal} 按宠物类型动态取值（猫/狗 = 30，其他 = 15）；
 * 里程碑本体为单独 mini-epic，{@code milestoneCompleted} 当前走零态（0），不硬依赖其落地。
 *
 * @param happyMomentCount   快乐时刻条数
 * @param consultCount       问诊（健康事件）次数
 * @param milestoneCompleted 已完成里程碑数（零态 = 0）
 * @param milestoneTotal     里程碑总数（按 pet_type：猫/狗 30，其他 15）
 */
public record ArchiveStatsResponse(
        long happyMomentCount,
        long consultCount,
        long milestoneCompleted,
        int milestoneTotal) {
}

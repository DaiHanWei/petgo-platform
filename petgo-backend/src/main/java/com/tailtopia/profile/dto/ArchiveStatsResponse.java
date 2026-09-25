package com.tailtopia.profile.dto;

/**
 * 成长档案统计栏（Story 2.4 AC5）。「快乐时刻 X 条 · 问诊 X 次」。
 *
 * <p>里程碑总数 {@code milestoneTotal} 按宠物类型动态取值（猫/狗 = 30，其他 = 15）；
 * 里程碑本体为单独 mini-epic，{@code milestoneCompleted} 当前走零态（0），不硬依赖其落地。
 *
 * @param happyMomentCount    快乐时刻条数
 * @param consultCount        问诊（健康事件）次数
 * @param milestoneCompleted  已完成里程碑数（零态 = 0）
 * @param milestoneTotal      里程碑总数（按 pet_type：猫/狗 30，其他 15）
 * @param healthRecordCount   结构化健康记录条数（V1.1.2：Diary 页头健康入口副文案按 0 / 非 0 切换，
 *        UI 稿 A4 近空态要求）。统计栏本身不展示该数字，只用于文案分支
 * @param milestoneUncelebrated 「已完成且未庆祝」条目数（V1.3.0 Story 1.4 · FR-111 · AD-A2.2）：
 *        档案 Tab 里程碑进度条上那枚角标就用这个数。
 *        <p>🔴 <b>刻意挂在本响应上，不新开接口、不为角标多发一次请求</b> —— 这里本来就要取
 *        「已完成 / 总数」，角标是同一次请求的第三个数。判据与全链路一致：完成行存在
 *        且 {@code celebrated_at IS NULL}（AD-A1.3），前端不得另立本地标记。
 *        <p>⚠️ 档案 Tab **只显示角标、永不弹全屏庆祝**（AD-A2.1）；补弹只挂里程碑列表页，
 *        否则用户从档案 Tab 点进列表页会被连弹两次
 */
public record ArchiveStatsResponse(
        long happyMomentCount,
        long consultCount,
        long milestoneCompleted,
        int milestoneTotal,
        long healthRecordCount,
        long milestoneUncelebrated) {
}

package com.tailtopia.notify.lifecycle;

/**
 * 生命周期推送的<b>分层落点</b>（留存运营作战手册 · 第二章「649 人不是一群人，是 5 群人」）。
 *
 * <p>同一个节点（D1/D3/D7/召回）对不同分层要说不同的话、跳不同的地方 ——
 * 给「还没建档」的人推「记录 Mochi 今天」是空转（他连 Mochi 是谁都还没告诉我们）。
 *
 * <p>variant 经通知的 {@code targetRef} 下发，客户端据此选深链落点
 * （沿用 {@code NAME_RESET}/{@code AVATAR_RESET} 的「单一类型 + variant 分流」范式，
 * 不为每个落点新增一个枚举值去撑爆 {@code ck_notifications_type}）。
 */
public enum LifecycleVariant {

    /**
     * 未建档 → 建档页。手册里 ROI 最高的一刀：557 人（85.8%）只打开过 1 天，
     * 1.1.0 还残留 506 人装了 App 却没建档 —— 获客成本已经沉没，只差这一步。
     */
    CREATE_PROFILE,

    /** 已建档 → 「+发布」预选成长日历。发布是全站唯一的强行为（30.9%），一切都往这儿引。 */
    RECORD,

    /** 已建档但仍未发布，D3 内容钩子 → Feed。先让他看见别人家的宠物，再谈自己动手。 */
    FEED,

    /** 已发布 → 成长档案 Tab 看周回顾。留存钩子 + 分享获客的起点。（V1.5.0 起计划器不再产出，保留供旧通知落点） */
    REVIEW,

    // ---- V1.5.0 onboarding-recall Story 6.1 · OR-23（旧值保留：旧通知 targetRef 仍可能是它们）----

    /** 去看推荐宠物（App → {@code /pet-recommendations}）：没宠物 / 待互动 / 待发帖（D3）。 */
    DISCOVER,

    /** 第 7 天还差 N 项新手任务（App → 里程碑分发页）。 */
    NEWBIE_TASKS,

    /** 第 7 天新手毕业（App → 里程碑分发页 + {@code celebrate=lulus}）；这是 Lulus 唯一一次推送。 */
    GRADUATED,

    /** 当天已来过 → D1 / D3 作废：<b>只写 mark</b>（防窗口第二天补推），不推送、不写通知、不下发。 */
    SKIPPED_ACTIVE
}

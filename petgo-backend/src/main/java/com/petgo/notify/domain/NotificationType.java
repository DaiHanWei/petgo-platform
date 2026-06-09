package com.petgo.notify.domain;

/**
 * 推送/通知类型（Story 6.1，落库 UPPER_SNAKE）。与 FR-38 深链七类目标 + 兽医端新请求对齐。
 *
 * <ul>
 *   <li>{@link #VET_REPLY} 兽医回复 → 问诊会话（6.2）。</li>
 *   <li>{@link #CONSULT_CLOSED} 问诊结束 → 评分（6.2/5.6）。</li>
 *   <li>{@link #CONTENT_LIKED} 被赞 → 内容详情（6.3）。</li>
 *   <li>{@link #CONTENT_COMMENTED} 被评 → 内容详情定位评论区（6.3）。</li>
 *   <li>{@link #NEW_CONSULT_REQUEST} 兽医端新请求 → 工作台（6.2）。</li>
 *   <li>{@link #PET_BIRTHDAY} 宠物生日 → 「+发布」预选成长日历（6.7，FR-40）。</li>
 *   <li>{@link #COMPANION_ANNIVERSARY} 陪伴纪念日 → 成长档案 Tab（6.7，FR-41）。</li>
 *   <li>{@link #MILESTONE_NODE} L级里程碑节点 → 成长档案 Tab→里程碑列表页（壳）（6.7，FR-42）。</li>
 *   <li>{@link #CONTENT_REMOVED} 内容因违规被运营下架 → 通知作者（3.7 AC3，无深链/无申诉入口，内容已 404）。</li>
 * </ul>
 *
 * <p>🔄 PRD V1.0.0 修订（Fx · 2026-06-08，决策 F2/F5）：新增后三类定时系统推送目标。
 * 本 Story 仅建枚举与深链路由地基；定时投递在 6.7。
 */
public enum NotificationType {
    VET_REPLY,
    CONSULT_CLOSED,
    CONTENT_LIKED,
    CONTENT_COMMENTED,
    NEW_CONSULT_REQUEST,
    PET_BIRTHDAY,
    COMPANION_ANNIVERSARY,
    MILESTONE_NODE,
    CONTENT_REMOVED
}

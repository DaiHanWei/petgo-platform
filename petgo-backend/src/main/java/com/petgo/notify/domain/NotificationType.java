package com.petgo.notify.domain;

/**
 * 推送/通知类型（Story 6.1，落库 UPPER_SNAKE）。与 FR-38 深链四类目标 + 兽医端新请求对齐。
 *
 * <ul>
 *   <li>{@link #VET_REPLY} 兽医回复 → 问诊会话（6.2）。</li>
 *   <li>{@link #CONSULT_CLOSED} 问诊结束 → 评分（6.2/5.6）。</li>
 *   <li>{@link #CONTENT_LIKED} 被赞 → 内容详情（6.3）。</li>
 *   <li>{@link #CONTENT_COMMENTED} 被评 → 内容详情定位评论区（6.3）。</li>
 *   <li>{@link #NEW_CONSULT_REQUEST} 兽医端新请求 → 工作台（6.2）。</li>
 * </ul>
 */
public enum NotificationType {
    VET_REPLY,
    CONSULT_CLOSED,
    CONTENT_LIKED,
    CONTENT_COMMENTED,
    NEW_CONSULT_REQUEST
}

package com.tailtopia.support.domain;

/**
 * 工单状态（Story 4.1，落库 varchar UPPER_SNAKE）。极简流转：
 * OPEN（用户建）→ IN_PROGRESS（admin 接手 4-4）→ RESOLVED（客服解决 4-4）→ CLOSED（7 天自动 或 CSAT 后 4-7）。
 * 本 story 只建 OPEN + 枚举定义；流转在 4-4/4-7。
 */
public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED
}

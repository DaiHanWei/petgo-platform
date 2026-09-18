package com.tailtopia.admin.moderation.dto;

import java.time.Instant;

/**
 * 工作台左栏一行（V1.3.0 Story 2.4）：统一工单行 + 已处理态补充信息（结果 / 操作人 / 时间 / 理由；待处理为 null）。
 *
 * @param tab        所属页签
 * @param row        统一工单行
 * @param result     已处理态：结果徽标（APPROVED / REJECTED / TAKEDOWN / DISMISSED / PASS / VIOLATION …），待处理为 null
 * @param handledBy  操作人显示名（后台账号），未知为 null
 * @param handledAt  处置时刻
 * @param reason     理由 / 判定依据（名称审核有；其余多为 null）
 */
public record ReviewQueueRow(ReviewTab tab, UnifiedTicketRow row, String result, String handledBy,
        Instant handledAt, String reason) {

    public boolean isAvatar() {
        return tab == ReviewTab.AVATAR && row.preview() != null && row.preview().startsWith("http");
    }

    /** 状态色点（UI 稿 9-3）：待处理 todo / 超期 hot / 终态 done。 */
    public String dot() {
        if (row.status() != TicketStatusBucket.PENDING) {
            return "done";
        }
        return row.overdue() ? "hot" : (row.score() >= 10 ? "wait" : "todo");
    }
}

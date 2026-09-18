package com.tailtopia.admin.moderation.dto;

import java.time.Instant;

/**
 * A2 左栏一行（V1.3.0 Story 2.5 AC2）：统一工单行 + 已处置态补充（结果 / 操作人 / 时间；待处置为 null）+ 限流中标记。
 *
 * @param result    已处置态结果徽标：WARNING / SUSPEND / DISMISSED / RESOLVED（无处置记录时退回工单状态）
 * @param handledBy 操作人显示名（后台账号），未知为 null
 * @param throttled 该账号当前处于限流生效态（Story 17.2 徽标）
 */
public record TicketQueueRow(UnifiedTicketRow row, String result, String handledBy, Instant handledAt,
        boolean throttled) {

    public boolean pending() {
        return row.status() == TicketStatusBucket.PENDING;
    }

    /** 状态色点：待处置 todo / 高分 wait / 终态 done。 */
    public String dot() {
        if (!pending()) {
            return "done";
        }
        return row.score() >= 10 ? "hot" : (row.score() >= 5 ? "wait" : "todo");
    }

    /** 勾选框 value：{@code 类型:id} 复合串（批量端点契约不变）。 */
    public String batchToken() {
        return row.type().name() + ":" + row.sourceId();
    }
}

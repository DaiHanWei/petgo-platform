package com.tailtopia.admin.moderation.dto;

/**
 * A2 处置成功后的 fragment 数据（V1.3.0 Story 2.5 AC8）：下一条 reportId（空 = 队列清空）、被处理行 id（oob delete）、两态计数（oob）。
 */
public record TicketDisposeResult(String nextId, long removedReportId, long pendingCount, long handledCount) {

    public String rowId() {
        return "ticket-row-" + removedReportId;
    }
}

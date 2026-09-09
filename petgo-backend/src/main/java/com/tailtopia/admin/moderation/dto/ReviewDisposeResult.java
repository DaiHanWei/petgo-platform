package com.tailtopia.admin.moderation.dto;

import java.util.Map;

/**
 * 处置成功后的 fragment 数据（V1.3.0 Story 2.4 AC5）：{@code nextId}（下一条 sourceId，空 = 队列清空）、
 * 各页签计数（oob 替换）、被处理行 id（oob delete）。{@code keepRow} 非空 = 该行未进终态（内容举报按帖聚合、
 * 单条驳回后该帖仍有待处理单），oob 重渲染该行而不是删除，且 {@code nextId} 指回它。
 */
public record ReviewDisposeResult(ReviewTab tab, String nextId, long removedSourceId, Map<ReviewTab, Long> counts,
        long otherPending, ReviewQueueRow keepRow) {

    public ReviewDisposeResult(ReviewTab tab, String nextId, long removedSourceId, Map<ReviewTab, Long> counts,
            long otherPending) {
        this(tab, nextId, removedSourceId, counts, otherPending, null);
    }

    public String rowId() {
        return "review-row-" + tab.param() + "-" + removedSourceId;
    }
}

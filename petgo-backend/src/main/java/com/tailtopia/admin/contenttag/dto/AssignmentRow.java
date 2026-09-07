package com.tailtopia.admin.contenttag.dto;

import java.time.Instant;

/**
 * 分配记录的一行（Story 11.2 · AB-10C）。
 *
 * @param permanent {@code endsAt} 为空 = 永久分配（本表比顶置排期多这一种情况）
 */
public record AssignmentRow(long id, long postId, String postSummary,
        long tagId, String tagName,
        Instant startsAt, Instant endsAt) {

    public boolean permanent() {
        return endsAt == null;
    }
}

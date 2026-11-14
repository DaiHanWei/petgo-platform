package com.tailtopia.healthreminder.service;

/**
 * 重建的触发原因（日志 / 取消原因判定用）。{@code deletedRecordId} 只在「记录删除」时非空：来源 = 被删记录的行取消原因为
 * {@code record_deleted}。
 */
public record RebuildTrigger(String source, Long deletedRecordId) {

    public static RebuildTrigger of(String source) {
        return new RebuildTrigger(source, null);
    }

    public static RebuildTrigger recordDeleted(long recordId) {
        return new RebuildTrigger("record_deleted", recordId);
    }
}

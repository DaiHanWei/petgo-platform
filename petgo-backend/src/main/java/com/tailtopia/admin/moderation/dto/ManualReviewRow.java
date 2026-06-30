package com.tailtopia.admin.moderation.dto;

import com.tailtopia.content.domain.ContentType;
import java.time.Instant;

/**
 * 人工审核队列行投影（Story 4.3）。content 预览经 {@code ContentService.findSummary}（不直读 content repo）。
 *
 * @param overdue {@code submitted_at} 超 24h 未处理 → 模板高亮（AC7）
 */
public record ManualReviewRow(long itemId, long contentId, ContentType type, String textPreview,
        Long authorId, Instant submittedAt, boolean overdue) {
}

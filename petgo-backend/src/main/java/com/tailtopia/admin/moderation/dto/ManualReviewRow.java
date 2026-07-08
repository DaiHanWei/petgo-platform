package com.tailtopia.admin.moderation.dto;

import com.tailtopia.admin.moderation.domain.ReviewContentType;
import com.tailtopia.admin.moderation.domain.ReviewPriority;
import com.tailtopia.content.domain.ContentType;
import java.time.Instant;

/**
 * 人工审核队列行投影（Story 4.3 + story 3 多态 + story 8 优先级/违规计数）。content 预览经门面
 * （{@code ContentService.findSummary} / {@code CommentService.findModerationSummary}），不直读 content repo。
 *
 * @param contentType 帖子 / 评论条目（story 3；模板据此区分展示）
 * @param type        帖子内容类型（评论行为 null）
 * @param overdue     {@code submitted_at} 超 24h 未处理 → 模板高亮（AC7）
 * @param priority    处理优先级（story 8，§5.1；模板 P0/P1/P2 徽标）
 * @param strikes     作者累计违规计数（story 8，§5.4；只读展示，读 {@code ViolationCountReader}）
 */
public record ManualReviewRow(long itemId, long contentId, ReviewContentType contentType,
        ContentType type, String textPreview, Long authorId, Instant submittedAt, boolean overdue,
        ReviewPriority priority, ViolationCounts strikes) {
}

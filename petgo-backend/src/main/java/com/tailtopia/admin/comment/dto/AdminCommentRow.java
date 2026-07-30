package com.tailtopia.admin.comment.dto;

import java.time.Instant;

/**
 * 后台评论管理行（Story 9.9；两线合并后状态口径切审核线可见性模型）。
 * {@code moderationStatus} 为 {@code CommentModerationStatus} 名（VISIBLE/UNDER_REVIEW/TAKEN_DOWN/
 * REJECTED/AUTHOR_DEACTIVATED）；{@code deleted} 为用户/级联软删（与可见性态正交，删除态不提供操作）。
 */
public record AdminCommentRow(
        long id,
        long postId,
        long authorId,
        String body,
        boolean deleted,
        String moderationStatus,
        Instant createdAt) {
}

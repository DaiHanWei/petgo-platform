package com.tailtopia.admin.comment.dto;

import java.time.Instant;

/** 后台评论管理行（Story 9.9）。 */
public record AdminCommentRow(
        long id,
        long postId,
        long authorId,
        String body,
        boolean deleted,
        Instant createdAt) {
}

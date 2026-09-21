package com.tailtopia.admin.comment.dto;

import java.time.Instant;

/**
 * B2 评论巡查的一行（V1.3.0 Story 7.2）：评论本身 + <b>所属帖子</b>与<b>作者</b>的展示字段。
 *
 * <p>🔴 帖子摘要与作者昵称都是<b>整页一次批量取</b>的（逐行查就是 N+1，与 B1 内容管理同一条纪律）。
 *
 * @param moderationStatus 审核线可见性态名（VISIBLE / UNDER_REVIEW / TAKEN_DOWN / REJECTED / AUTHOR_DEACTIVATED）
 * @param deleted          用户自删 / 级联软删 —— 与可见性态<b>正交</b>，删除态不提供任何处置（Dev Notes 🔴）
 * @param postPreview      所属帖子的正文摘要（可空：帖子已被硬删或取不到）
 * @param postImage        所属帖子首图（可空）
 * @param authorName       作者昵称（注销者为空，由 {@code authorDeleted} 区分）
 */
public record CommentInspectRow(
        long id,
        long postId,
        long authorId,
        String body,
        boolean deleted,
        String moderationStatus,
        Instant createdAt,
        String postPreview,
        String postImage,
        String authorName,
        boolean authorDeleted) {

    /** 可下架：未删且当前可见（与 9.9 行内按钮的条件逐字一致）。 */
    public boolean canTakedown() {
        return !deleted && "VISIBLE".equals(moderationStatus);
    }

    /** 可恢复：未删且处于下架 / 拒绝态（与 9.9 行内按钮的条件逐字一致）。 */
    public boolean canRestore() {
        return !deleted && ("TAKEN_DOWN".equals(moderationStatus) || "REJECTED".equals(moderationStatus));
    }
}

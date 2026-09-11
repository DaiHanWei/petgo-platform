package com.tailtopia.content.dto;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import java.time.Instant;
import java.util.List;

/**
 * 评论投影（Story 3.3，只读）。Jackson NON_NULL；时间 ISO-8601 UTC。
 *
 * <p>一级评论：{@code replyCount}（二级总数）+ {@code replies}（前 3 条二级）非空；
 * 二级回复：{@code replyCount}/{@code replies} 为 null。作者注销匿名化（NFR-8）。
 *
 * @param replyCount       一级评论的二级回复总数（二级回复为 null）
 * @param replies          一级评论的前 3 条二级回复（二级回复为 null）
 * @param moderationStatus 审核可见性态（story 3）：VISIBLE 无标签；TAKEN_DOWN 渲染「仅你可见」灰标签
 *                         （仅作者本人会收到非 VISIBLE 行，读路径已按 viewer 过滤）
 * @param likeCount        点赞数（V1.3.0 Story 2.4）。**实时聚合，库里没有计数列**；
 *                         批量取数，无人点赞的评论为 0
 * @param liked            当前查看者是否已赞（游客恒 false）。同样批量取数，不逐条查
 */
public record CommentResponse(
        Long id,
        long authorId,
        String authorNickname,
        String authorAvatarUrl,
        boolean authorDeleted,
        // 运营标签（V1.1.6 Story 5.1 · FR-74）。⚠️ 评论区是最容易退化成逐条查的地方，
        // 但取数在作者投影里整批完成，这里只是把结果原样带出来。
        java.util.List<com.tailtopia.auth.dto.UserTagView> authorTags,
        String body,
        Instant createdAt,
        Integer replyCount,
        List<CommentResponse> replies,
        String moderationStatus,
        long likeCount,
        boolean liked) {

    /** 二级回复（无嵌套）。 */
    public static CommentResponse reply(Comment c, AuthorView author, long likeCount, boolean liked) {
        return new CommentResponse(c.getId(), c.getAuthorId(), author.nickname(),
                author.avatarUrl(), author.deleted(), author.tags().isEmpty() ? null : author.tags(),
                c.getBody(), c.getCreatedAt(), null, null, statusName(c), likeCount, liked);
    }

    /** 一级评论（带 replyCount + 前 3 条二级）。 */
    public static CommentResponse topLevel(Comment c, AuthorView author, int replyCount,
            List<CommentResponse> firstReplies, long likeCount, boolean liked) {
        return new CommentResponse(c.getId(), c.getAuthorId(), author.nickname(),
                author.avatarUrl(), author.deleted(), author.tags().isEmpty() ? null : author.tags(),
                c.getBody(), c.getCreatedAt(), replyCount, firstReplies, statusName(c),
                likeCount, liked);
    }

    private static String statusName(Comment c) {
        CommentModerationStatus s = c.getModerationStatus();
        return s == null ? CommentModerationStatus.VISIBLE.name() : s.name();
    }
}

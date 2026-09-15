package com.tailtopia.content.dto;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.mention.dto.MentionView;
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
        /**
         * 这条评论里的 @（V1.3.0 batch-b1 Story 3.3 · AC1/AC3/AC4）。
         *
         * <p>🔴 <b>能不能点、显示什么昵称都是服务端算好的</b>（见 {@link MentionView}）。
         * 空表不下发 —— 评论区一页 40 行，每行挂一个空数组是白占体积。
         */
        List<MentionView> mentions) {

    /** 二级回复（无嵌套）。{@code mentions} 为 @ 投影（Story 3.3），没 @ 人时传 null。 */
    public static CommentResponse reply(Comment c, AuthorView author, List<MentionView> mentions) {
        return new CommentResponse(c.getId(), c.getAuthorId(), author.nickname(),
                author.avatarUrl(), author.deleted(), author.tags().isEmpty() ? null : author.tags(),
                c.getBody(), c.getCreatedAt(), null, null, statusName(c), mentions);
    }

    /**
     * 一级评论（带 replyCount + 前 3 条二级）。{@code mentions} 为 @ 投影（Story 3.3），
     * 没 @ 人时传 null。
     *
     * <p>⚠️ <b>刻意只有这一个签名</b>：留一个"不带 @"的窄重载，下一个出口按自动补全挑了它
     * 就会让那一屏的 @ 全变纯文字 —— 没有编译错误、没有测试兜底（code-review 2026-09-15）。
     */
    public static CommentResponse topLevel(Comment c, AuthorView author, int replyCount,
            List<CommentResponse> firstReplies, List<MentionView> mentions) {
        return new CommentResponse(c.getId(), c.getAuthorId(), author.nickname(),
                author.avatarUrl(), author.deleted(), author.tags().isEmpty() ? null : author.tags(),
                c.getBody(), c.getCreatedAt(), replyCount, firstReplies, statusName(c), mentions);
    }

    private static String statusName(Comment c) {
        CommentModerationStatus s = c.getModerationStatus();
        return s == null ? CommentModerationStatus.VISIBLE.name() : s.name();
    }
}

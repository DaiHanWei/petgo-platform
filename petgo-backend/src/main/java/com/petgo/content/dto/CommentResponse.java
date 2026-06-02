package com.petgo.content.dto;

import com.petgo.auth.dto.AuthorView;
import com.petgo.content.domain.Comment;
import java.time.Instant;
import java.util.List;

/**
 * 评论投影（Story 3.3，只读）。Jackson NON_NULL；时间 ISO-8601 UTC。
 *
 * <p>一级评论：{@code replyCount}（二级总数）+ {@code replies}（前 3 条二级）非空；
 * 二级回复：{@code replyCount}/{@code replies} 为 null。作者注销匿名化（NFR-8）。
 *
 * @param replyCount 一级评论的二级回复总数（二级回复为 null）
 * @param replies    一级评论的前 3 条二级回复（二级回复为 null）
 */
public record CommentResponse(
        Long id,
        long authorId,
        String authorNickname,
        String authorAvatarUrl,
        boolean authorDeleted,
        String body,
        Instant createdAt,
        Integer replyCount,
        List<CommentResponse> replies) {

    /** 二级回复（无嵌套）。 */
    public static CommentResponse reply(Comment c, AuthorView author) {
        return new CommentResponse(c.getId(), c.getAuthorId(), author.nickname(),
                author.avatarUrl(), author.deleted(), c.getBody(), c.getCreatedAt(), null, null);
    }

    /** 一级评论（带 replyCount + 前 3 条二级）。 */
    public static CommentResponse topLevel(Comment c, AuthorView author, int replyCount,
            List<CommentResponse> firstReplies) {
        return new CommentResponse(c.getId(), c.getAuthorId(), author.nickname(),
                author.avatarUrl(), author.deleted(), c.getBody(), c.getCreatedAt(),
                replyCount, firstReplies);
    }
}

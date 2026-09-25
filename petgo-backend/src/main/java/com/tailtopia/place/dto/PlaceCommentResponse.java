package com.tailtopia.place.dto;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.place.domain.PlaceComment;
import java.time.Instant;

/**
 * 场所评论投影（V1.3.0 batch-b1 Story 1.7）。Jackson NON_NULL；时间 ISO-8601 UTC。
 *
 * <h2>🔴 这里**没有**的字段</h2>
 * <ul>
 *   <li>**没有** {@code replyCount} / {@code replies} —— 一级 only，没有楼中楼（AC2）；</li>
 *   <li>**没有**对评论本身的点赞数 —— UI 稿 A4 那条评论下画的「👍 12 · 👎 0」是稿子画松了：
 *       按 B1-D3，那两个数字是**场所维度的态度累计**（在计数行，Story 1.8），
 *       每条评论自己带的只是**它这一条的态度**（{@link #attitude} 二值或无）。</li>
 * </ul>
 *
 * @param attitude         该条评论作者的态度：{@code RECOMMEND} / {@code NOT_RECOMMEND} /
 *                         **省略**（未表态）。不是计数，是这一条的立场
 * @param moderationStatus 审核态。非 VISIBLE 的行只会下发给作者本人（读路径已按 viewer 过滤），
 *                         前端据此渲染「仅你可见」灰标签
 * @param mine             是否本人发的 —— 前端据此决定要不要给删除入口（AC7）。
 *                         🔴 **服务端算给它**，不让客户端拿 authorId 自己比：前端改一行就能
 *                         看到删除按钮，删除本身的权限校验在服务端（那才是真正的门）
 */
public record PlaceCommentResponse(
        Long id,
        long authorId,
        String authorNickname,
        String authorAvatarUrl,
        boolean authorDeleted,
        String body,
        String attitude,
        Instant createdAt,
        String moderationStatus,
        boolean mine) {

    public static PlaceCommentResponse of(PlaceComment c, AuthorView author, Long viewerId) {
        CommentModerationStatus s = c.getModerationStatus();
        return new PlaceCommentResponse(
                c.getId(),
                c.getAuthorId(),
                author.nickname(),
                author.avatarUrl(),
                author.deleted(),
                c.getBody(),
                c.getAttitude() == null ? null : c.getAttitude().name(),
                c.getCreatedAt(),
                (s == null ? CommentModerationStatus.VISIBLE : s).name(),
                viewerId != null && viewerId.equals(c.getAuthorId()));
    }
}

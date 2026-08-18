package com.tailtopia.content.dto;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import java.time.Instant;
import java.util.List;

/**
 * 内容详情投影（Story 3.3）。Jackson NON_NULL；时间 ISO-8601 UTC。
 *
 * <p>{@code likeCount}/{@code liked} 在本 Story 为占位（0 / false），由 Story 3.4 接入真实点赞表；
 * {@code commentCount} 取自 comments 表实计。{@code isAuthor} 供前端「···」删除入口可见性（行为在 3.6）。
 * 作者注销匿名化（NFR-8）→ 仍 200，作者投影为「已注销」（前端本地化），非 404。
 *
 * @param liked    当前用户是否已赞（游客 false）；3.4 接入
 * @param isAuthor 当前用户是否作者（删除入口可见性）
 */
public record ContentDetailResponse(
        Long id,
        long authorId,
        String authorNickname,
        String authorAvatarUrl,
        boolean authorDeleted,
        // 运营标签（V1.1.6 Story 5.1 · FR-74）。最多 3 个；注销作者恒为空表。
        java.util.List<com.tailtopia.auth.dto.UserTagView> authorTags,
        // 内容装饰标签（V1.1.6 Story 5.2 · FR-75）。空表不下发。
        java.util.List<ContentTagView> decorationTags,
        ContentType type,
        String body,
        List<String> imageUrls,
        long likeCount,
        long commentCount,
        boolean liked,
        boolean isAuthor,
        Instant createdAt) {

    public static ContentDetailResponse of(ContentPost p, AuthorView author, long likeCount,
            long commentCount, boolean liked, boolean isAuthor,
            List<ContentTagView> decorationTags) {
        return new ContentDetailResponse(
                p.getId(), p.getAuthorId(), author.nickname(), author.avatarUrl(),
                author.deleted(), author.tags().isEmpty() ? null : author.tags(),
                (decorationTags == null || decorationTags.isEmpty()) ? null : decorationTags,
                p.getType(), p.getText(), p.getImageUrls(),
                likeCount, commentCount, liked, isAuthor, p.getCreatedAt());
    }
}

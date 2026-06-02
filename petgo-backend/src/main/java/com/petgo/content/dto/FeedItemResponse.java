package com.petgo.content.dto;

import com.petgo.auth.dto.AuthorView;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import java.time.Instant;
import java.util.List;

/**
 * Feed 卡片投影（Story 3.2，AC2）。Jackson NON_NULL；时间 ISO-8601 UTC。
 *
 * <p>**不含 likeCount/commentCount**（FR-17：卡片不展示计数）。{@code body} 给全文，前端截前 2 行。
 * {@code firstImageUrl} 可空（无图 → 纯文字卡）。作者注销时 {@code authorDeleted=true}、
 * 昵称/头像为 null（前端本地化「已注销用户」+ 默认头像，头像不可点 — NFR-8 / Story 3.8）。
 *
 * @param id            内容 id
 * @param authorId      作者 id（注销后仍返回）
 * @param authorNickname 作者昵称（注销时 null）
 * @param authorAvatarUrl 作者头像（注销时 null）
 * @param authorDeleted 作者是否已注销
 * @param type          内容类型
 * @param body          正文全文（可空）
 * @param firstImageUrl 首图（可空，无图为纯文字卡）
 * @param createdAt     发布时刻（ISO UTC）
 */
public record FeedItemResponse(
        Long id,
        long authorId,
        String authorNickname,
        String authorAvatarUrl,
        boolean authorDeleted,
        ContentType type,
        String body,
        String firstImageUrl,
        Instant createdAt) {

    public static FeedItemResponse of(ContentPost p, AuthorView author) {
        List<String> images = p.getImageUrls();
        String firstImage = (images != null && !images.isEmpty()) ? images.get(0) : null;
        return new FeedItemResponse(
                p.getId(),
                p.getAuthorId(),
                author.nickname(),
                author.avatarUrl(),
                author.deleted(),
                p.getType(),
                p.getText(),
                firstImage,
                p.getCreatedAt());
    }
}

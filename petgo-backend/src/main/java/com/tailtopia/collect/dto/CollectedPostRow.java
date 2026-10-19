package com.tailtopia.collect.dto;

import com.tailtopia.content.domain.ContentType;
import java.time.Instant;

/**
 * 我的帖子收藏一行（V1.4.0 ④ Story 2.2 · AD-11 · UI E2 / E3）。Jackson NON_NULL。
 *
 * <p>🔴 {@code available = false} 时 {@code post} 整个为 null（JSON 省略）：不下发原正文、作者、封面、类型（NFR2）。
 * 不下发收藏行 id、userId、authorId。{@code postId} 沿用存量 {@code /api/v1/content-posts/{id}} 寻址。
 */
public record CollectedPostRow(long postId, Instant collectedAt, boolean available, Post post) {

    /**
     * @param excerpt  正文去首尾空白后前 120 个 code point（帖子没有标题列）；空文本为 null
     * @param coverUrl 首图原样（与 Feed 同口径，App 自行取缩略图）；无图为 null
     */
    public record Post(ContentType type, String excerpt, String coverUrl, Author author) {
    }

    public record Author(String nickname, String avatarUrl) {
    }
}

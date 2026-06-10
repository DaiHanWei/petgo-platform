package com.petgo.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.petgo.content.domain.ContentType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标：内容详情 + 评论两级 wire（CROSS-STORY-DECISIONS C5）。
 *
 * <p>三方同步点：
 * <ul>
 *   <li>App  —— {@code content/domain/content_detail.dart}（{@code ContentDetail.fromJson}）、
 *              {@code content/domain/comment.dart}（{@code Comment.fromJson}/{@code CommentPage.fromJson}）</li>
 *   <li>Mock —— {@code mock_backend.dart}（{@code /content-posts/{id}}、{@code .../comments}）</li>
 * </ul>
 *
 * <p>注意 record 的 boolean 分量（{@code authorDeleted}/{@code liked}/{@code isAuthor}）Jackson 按分量名原样
 * 落键（不剥 {@code is}），App 读 {@code json['isAuthor']} 等据此对齐。
 */
class ContentDetailAndCommentContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void contentDetailHasExactlyTheContractFields() {
        ContentDetailResponse d = new ContentDetailResponse(
                1L, 7L, "小明", "https://cdn/a.jpg", false, ContentType.DAILY, "正文",
                List.of("https://cdn/1.jpg", "https://cdn/2.jpg"), 5L, 2L, true, false,
                Instant.parse("2026-06-05T00:00:00Z"));

        assertThat(wire(d).keySet()).isEqualTo(Set.of(
                "id", "authorId", "authorNickname", "authorAvatarUrl", "authorDeleted", "type",
                "body", "imageUrls", "likeCount", "commentCount", "liked", "isAuthor", "createdAt"));
    }

    @Test
    void topLevelCommentHasNineFields() {
        CommentResponse top = new CommentResponse(
                10L, 7L, "小明", "https://cdn/a.jpg", false, "评论正文",
                Instant.parse("2026-06-05T00:00:00Z"), 3, List.of());

        assertThat(wire(top).keySet()).isEqualTo(Set.of(
                "id", "authorId", "authorNickname", "authorAvatarUrl", "authorDeleted",
                "body", "createdAt", "replyCount", "replies"));
    }

    @Test
    void replyCommentOmitsReplyCountAndReplies() {
        // 二级回复无嵌套：replyCount/replies 为 null → NON_NULL 省略。
        CommentResponse reply = new CommentResponse(
                11L, 8L, "小红", null, false, "回复正文",
                Instant.parse("2026-06-05T00:00:00Z"), null, null);

        assertThat(wire(reply).keySet()).isEqualTo(Set.of(
                "id", "authorId", "authorNickname", "authorDeleted", "body", "createdAt"));
        assertThat(wire(reply)).doesNotContainKey("replyCount");
    }

    @Test
    void commentPageEnvelopeShape() {
        CommentPageResponse page = new CommentPageResponse(List.of(), "cur", true);
        assertThat(wire(page).keySet()).isEqualTo(Set.of("items", "nextCursor", "hasMore"));

        // 末页省略 nextCursor。
        CommentPageResponse last = new CommentPageResponse(List.of(), null, false);
        assertThat(wire(last).keySet()).isEqualTo(Set.of("items", "hasMore"));
    }
}

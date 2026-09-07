package com.tailtopia.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.content.domain.ContentType;
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
                1L, 7L, "小明", "https://cdn/a.jpg", false,
                // V1.1.6 Story 5.1：运营标签随作者一起下发；**空表不下发**（NON_NULL 省略）。
                List.of(new com.tailtopia.auth.dto.UserTagView("vet", "兽医", "🩺", "已认证兽医", "#F6A609")),
                List.of(new ContentTagView("editor_pick", "编辑推荐", "🏆", "被官方选中的优质内容",
                        "#F6A609", "#F0596E")),
                ContentType.DAILY, "正文",
                List.of("https://cdn/1.jpg", "https://cdn/2.jpg"), 5L, 2L, true, false,
                // V1.1.6 Story 10.1：**恒下发**（与 FeedItemResponse 同口径）。
                // 补它的唯一理由是埋点 E-11 的加粗属性 is_private_diary。
                com.tailtopia.content.domain.ContentVisibility.PRIVATE,
                Instant.parse("2026-06-05T00:00:00Z"));

        assertThat(wire(d).keySet()).isEqualTo(Set.of(
                "id", "authorId", "authorNickname", "authorAvatarUrl", "authorDeleted",
                "authorTags", "decorationTags", "type",
                "body", "imageUrls", "likeCount", "commentCount", "liked", "isAuthor",
                "visibility", "createdAt"));
        // 线格式是枚举名大写（客户端按字符串比 PRIVATE / PUBLIC，不做数字映射）。
        assertThat(wire(d)).containsEntry("visibility", "PRIVATE");
    }

    @Test
    void topLevelCommentHasModerationStatusField() {
        // story 3：新增 moderationStatus 字段（VISIBLE 无标签；TAKEN_DOWN 前端渲染「仅你可见」）。
        CommentResponse top = new CommentResponse(
                10L, 7L, "小明", "https://cdn/a.jpg", false,
                List.of(new com.tailtopia.auth.dto.UserTagView("vet", "兽医", "🩺", "已认证兽医", "#F6A609")),
                "评论正文",
                Instant.parse("2026-06-05T00:00:00Z"), 3, List.of(), "VISIBLE");

        assertThat(wire(top).keySet()).isEqualTo(Set.of(
                "id", "authorId", "authorNickname", "authorAvatarUrl", "authorDeleted",
                "authorTags", "body", "createdAt", "replyCount", "replies", "moderationStatus"));
    }

    @Test
    void replyCommentOmitsReplyCountAndReplies() {
        // 二级回复无嵌套：replyCount/replies 为 null → NON_NULL 省略；moderationStatus 始终下发。
        CommentResponse reply = new CommentResponse(
                // 无标签 → authorTags 为 null → NON_NULL 省略（下方字段集里因此没有它）。
                11L, 8L, "小红", null, false, null, "回复正文",
                Instant.parse("2026-06-05T00:00:00Z"), null, null, "VISIBLE");

        assertThat(wire(reply).keySet()).isEqualTo(Set.of(
                "id", "authorId", "authorNickname", "authorDeleted", "body", "createdAt",
                "moderationStatus"));
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

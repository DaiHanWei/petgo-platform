package com.tailtopia.place.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.place.domain.PlaceComment;
import com.tailtopia.place.domain.PlaceCommentAttitude;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标（CROSS-STORY C4/C5）：场所评论对外 JSON 形状
 * （V1.3.0 batch-b1 Story 1.7 · AC2/AC3/AC7）。
 *
 * <p>与详情那份一样，**有一半是在断言「没有什么」**：没有 replyCount / replies
 * （一级 only），没有对评论本身的点赞数。字段不存在，界面就画不出来。
 */
class PlaceCommentResponseContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static final AuthorView AUTHOR =
            new AuthorView(9L, "Budi", "https://cdn/a.jpg", false, List.of());

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    private static PlaceComment comment(PlaceCommentAttitude attitude) {
        PlaceComment c = PlaceComment.createUnderReview(42L, 9L, "Enak buat kerja", attitude);
        // id 与 createdAt 都由 JPA 在持久化时赋值（@GeneratedValue / @PrePersist）。
        // 单元测试不过 EntityManager，所以这里补上 —— 否则 NON_NULL 会把 createdAt 整个省掉，
        // 而线上那份响应一定带着它，契约就对不上了。
        set(c, "id", 7L);
        set(c, "createdAt", java.time.Instant.parse("2026-09-15T08:00:00Z"));
        return c;
    }

    private static void set(PlaceComment c, String field, Object value) {
        try {
            var f = PlaceComment.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(c, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PlaceComment." + field + " 字段名变了，改这里", e);
        }
    }

    @Test
    void fullShapeIsTheAgreedFieldSet() {
        Map<String, Object> w = wire(
                PlaceCommentResponse.of(comment(PlaceCommentAttitude.RECOMMEND), AUTHOR, 9L));

        assertThat(w.keySet()).isEqualTo(Set.of(
                "id", "authorId", "authorNickname", "authorAvatarUrl", "authorDeleted",
                "body", "attitude", "createdAt", "moderationStatus", "mine"));
        assertThat(w).containsEntry("attitude", "RECOMMEND");
        assertThat(w).containsEntry("body", "Enak buat kerja");
    }

    /** 🔴 AC3：没表态时 **省略** attitude 键（不是空串、不是 "NONE"）。 */
    @Test
    void attitudeKeyIsAbsentWhenNotGiven() {
        assertThat(wire(PlaceCommentResponse.of(comment(null), AUTHOR, 9L)))
                .doesNotContainKey("attitude");
    }

    /**
     * 🔴 AC2 的反向验收：**没有**任何回复相关字段。
     *
     * <p>内容评论的 {@code CommentResponse} 带 {@code replyCount} / {@code replies}；
     * 场所评论一条都不能有 —— 有了字段，客户端就会照着画一个"回复"入口。
     */
    @Test
    void noReplyFieldsAtAll() {
        assertThat(wire(PlaceCommentResponse.of(comment(null), AUTHOR, 9L)).keySet())
                .doesNotContain("replyCount", "replies", "parentId");
    }

    /** 也没有对评论本身的点赞数（UI 稿 A4 那条评论下的「👍 12」是稿子画松了，见 DTO 注释）。 */
    @Test
    void noPerCommentLikeCounts() {
        assertThat(wire(PlaceCommentResponse.of(comment(null), AUTHOR, 9L)).keySet())
                .doesNotContain("likeCount", "recommendCount", "notRecommendCount", "liked");
    }

    /**
     * 🔴 AC7：{@code mine} 由**服务端**算，不是让客户端拿 authorId 自己比。
     *
     * <p>（比出来的值当然一样 —— 要紧的是"删除入口该不该画"这件事有一个服务端口径，
     * 而删除本身的权限校验在 service 里，那才是真正的门。）
     */
    @Test
    void mineIsTrueOnlyForTheAuthor() {
        assertThat(wire(PlaceCommentResponse.of(comment(null), AUTHOR, 9L)))
                .containsEntry("mine", true);
        assertThat(wire(PlaceCommentResponse.of(comment(null), AUTHOR, 999L)))
                .containsEntry("mine", false);
        assertThat(wire(PlaceCommentResponse.of(comment(null), AUTHOR, null)))
                .as("游客没有『自己的评论』")
                .containsEntry("mine", false);
    }

    /** 注销作者：昵称/头像省略、authorDeleted=true（NFR-8 匿名化）。 */
    @Test
    void deactivatedAuthorIsAnonymized() {
        Map<String, Object> w = wire(
                PlaceCommentResponse.of(comment(null), AuthorView.anonymized(9L), 1L));
        assertThat(w).doesNotContainKey("authorNickname");
        assertThat(w).doesNotContainKey("authorAvatarUrl");
        assertThat(w).containsEntry("authorDeleted", true);
    }

    /** 分页信封带 total（详情页标题「KOMENTAR (3)」要用）。 */
    @Test
    void pageEnvelopeCarriesTotal() {
        Map<String, Object> w = wire(new PlaceCommentPageResponse(List.of(), null, false, 3L));
        assertThat(w).containsEntry("total", 3L);
        assertThat(w).containsEntry("hasMore", false);
        assertThat(w).doesNotContainKey("nextCursor");
    }
}

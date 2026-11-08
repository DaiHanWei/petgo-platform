package com.tailtopia.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0：帖子出参 {@code topicNames} 与发帖响应 {@code topics}（V1.4.2 social-topic · Story 4.1 · AC6 · D-13 / R-2）。
 * 空表 → 字段 null → NON_NULL 下无该键（不带话题的帖响应体逐字节不变）；非空原样（标准名）。
 */
class FeedItemResponseTopicNamesTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static ContentPost post() {
        ContentPost p = ContentPost.publish(7L, ContentType.DAILY, null, "#KucingLucu hari ini", List.of());
        try {
            var id = ContentPost.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(p, 42L);
            var at = ContentPost.class.getDeclaredField("createdAt");
            at.setAccessible(true);
            at.set(p, Instant.parse("2026-11-04T00:00:00Z"));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return p;
    }

    private static FeedItemResponse feed(List<String> topicNames) {
        return FeedItemResponse.of(post(), new AuthorView(7L, "u7", null, false, List.of()), 0L, false, 0L,
                null, null, null, null, topicNames);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void emptyOrNullTopicNamesAreOmitted() {
        assertThat(feed(List.of()).topicNames()).isNull();
        assertThat(wire(feed(List.of()))).doesNotContainKey("topicNames");
        assertThat(wire(feed(null))).doesNotContainKey("topicNames");
        assertThat(wire(feed(null)).keySet()).isEqualTo(wire(feed(List.of())).keySet());
    }

    @Test
    void nonEmptyTopicNamesAreSerializedVerbatimAndCopied() {
        List<String> names = new ArrayList<>(List.of("kucinglucu", "anabul"));
        FeedItemResponse item = feed(names);
        names.add("x");
        assertThat(wire(item)).containsEntry("topicNames", List.of("kucinglucu", "anabul"));
        // withReason 不丢 topicNames
        assertThat(item.withReason(FeedItemResponse.REASON_FOLLOWED_USER).topicNames())
                .containsExactly("kucinglucu", "anabul");
    }

    @Test
    void detailTopicNamesFollowTheSameRule() {
        AuthorView author = new AuthorView(7L, "u7", null, false, List.of());
        ContentDetailResponse none = ContentDetailResponse.of(post(), author, 0L, 0L, false, false,
                null, null, null, null, null, null, null, null, false, null, List.of());
        assertThat(wire(none)).doesNotContainKey("topicNames");
        ContentDetailResponse some = ContentDetailResponse.of(post(), author, 0L, 0L, false, false,
                null, null, null, null, null, null, null, null, false, null, List.of("kucinglucu"));
        assertThat(wire(some)).containsEntry("topicNames", List.of("kucinglucu"));
    }

    @Test
    void publishResponseTopicsShapeAndOmission() {
        assertThat(wire(ContentPostResponse.from(post()))).doesNotContainKey("topics");
        assertThat(wire(ContentPostResponse.from(post(), List.of()))).doesNotContainKey("topics");
        Map<String, Object> m = wire(ContentPostResponse.from(post(),
                List.of(new TopicRef("kucinglucu", false, true))));
        assertThat(m).containsEntry("topics",
                List.of(Map.of("name", "kucinglucu", "official", false, "created", true)));
        // 兼容构造器不变：7 参 / 8 参 topics 为 null。
        assertThat(new ContentPostResponse(1L, ContentType.DAILY, null, "t", null, null, Instant.EPOCH).topics())
                .isNull();
    }
}

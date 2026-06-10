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
 * L0 契约金标：钉死 Feed 对外 JSON 形状（CROSS-STORY-DECISIONS C4 / C5 的首个落地范例）。
 *
 * <p><b>三方必须同步，任一漂移即契约破坏（本测试会红）：</b>
 * <ul>
 *   <li>后端  —— {@link FeedPageResponse} / {@link FeedItemResponse}（本测试钉的对象）</li>
 *   <li>App   —— {@code petgo_app/lib/features/content/domain/feed_item.dart}
 *               （{@code FeedItem.fromJson} / {@code FeedPage.fromJson}）</li>
 *   <li>Mock  —— {@code petgo_app/lib/core/mock/mock_backend.dart}（{@code _post} / {@code _envelope}）</li>
 * </ul>
 *
 * <p>改了上面任一对外 DTO 字段，本测试红 —— 红了就<b>同步改 App 两处</b>，别只改后端（C4：后端主导，
 * mock 是镜像）。纯 Jackson 序列化、<b>无 Spring 上下文 / 无 DB</b> → 云端 headless 可跑（L0）。
 *
 * <p>序列化器镜像生产配置：{@code application.yml → spring.jackson.default-property-inclusion=non_null}
 * （注销作者 / 纯文字卡 / 末页 cursor 等 null 字段须省略，不外泄、不占位）。
 */
class FeedResponseContractTest {

    /** 与生产一致的 NON_NULL 序列化（Jackson 3 / {@code tools.jackson}，不可用 com.fasterxml 的 databind）。 */
    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    /** FeedItemResponse 完整形态的权威字段集 —— 必须与 App FeedItem.fromJson 读取的键一一对应。 */
    private static final Set<String> ITEM_FIELDS = Set.of(
            "id", "authorId", "authorNickname", "authorAvatarUrl", "authorDeleted",
            "type", "body", "firstImageUrl", "likeCount", "createdAt");

    /** 游标分页信封字段集 —— 对应 App FeedPage.fromJson 的 {items, nextCursor, hasMore}。 */
    private static final Set<String> ENVELOPE_FIELDS = Set.of("items", "nextCursor", "hasMore");

    private Map<String, Object> wire(Object dto) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = json.convertValue(dto, Map.class);
        return m;
    }

    @Test
    void feedItemFullShapeHasExactlyTheContractFields() {
        FeedItemResponse item = new FeedItemResponse(
                42L, 7L, "小明", "https://cdn.petgo/p/a.jpg", false,
                ContentType.DAILY, "今天带毛孩子去遛弯", "https://cdn.petgo/p/img.jpg", 3L,
                Instant.parse("2026-06-05T00:00:00Z"));

        Map<String, Object> m = wire(item);
        assertThat(m.keySet())
                .as("FeedItemResponse 对外字段集必须与 App FeedItem.fromJson 完全一致（C5）")
                .isEqualTo(ITEM_FIELDS);
        // 枚举落 UPPER_SNAKE 线格式（App FeedCategory.wire / FeedItem.type 依赖此）。
        assertThat(m.get("type")).isEqualTo("DAILY");
    }

    @Test
    void deletedAuthorAndTextlessCardOmitsNullablesButKeepsRequired() {
        // 注销作者（NFR-8：昵称/头像不外泄）+ 纯文字无图卡（firstImageUrl null）+ 无正文。
        FeedItemResponse item = new FeedItemResponse(
                42L, 7L, null, null, true,
                ContentType.GROWTH_MOMENT, null, null, 0L,
                Instant.parse("2026-06-05T00:00:00Z"));

        Map<String, Object> m = wire(item);
        assertThat(m.keySet())
                .as("NON_NULL：可空字段缺省即省略；必填恒在（App 侧均 null 容忍）")
                .isEqualTo(Set.of("id", "authorId", "authorDeleted", "type", "likeCount", "createdAt"));
        assertThat(m).doesNotContainKey("authorNickname"); // 注销不外泄 PII
        assertThat(m).doesNotContainKey("firstImageUrl");   // 纯文字卡，App hasImage=false
    }

    @Test
    void envelopeShapeMatchesCursorContractWhenHasMore() {
        FeedPageResponse page = new FeedPageResponse(List.of(), "eyJjcmVhdGVkQXQiOjF9", true);

        Map<String, Object> m = wire(page);
        assertThat(m.keySet()).isEqualTo(ENVELOPE_FIELDS);
        assertThat(m.get("items")).isInstanceOf(List.class);
        assertThat(m.get("hasMore")).isEqualTo(true);
    }

    @Test
    void envelopeOmitsNextCursorOnLastPage() {
        // 末页：hasMore=false → nextCursor=null → NON_NULL 省略（App FeedPage.fromJson 容忍缺省）。
        FeedPageResponse page = new FeedPageResponse(List.of(), null, false);

        Map<String, Object> m = wire(page);
        assertThat(m).doesNotContainKey("nextCursor");
        assertThat(m.keySet()).isEqualTo(Set.of("items", "hasMore"));
    }
}

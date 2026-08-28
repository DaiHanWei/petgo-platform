package com.tailtopia.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.ImageSize;
import java.time.Instant;
import java.util.Arrays;
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
            "type", "body", "firstImageUrl", "likeCount", "createdAt", "visibility",
            // V1.1.6 Story 3.1（AD-7 Rule 1）：整组图片 + 是否已赞 + 评论数。
            // ⚠️ firstImageUrl **保留不动** —— 老客户端还在读它。
            "imageUrls", "imageSizes", "liked", "commentCount",
            // V1.1.6 Story 5.1（FR-74）：运营标签随作者下发。**空表不下发**（NON_NULL 省略）。
            "authorTags",
            // V1.1.6 Story 5.2（FR-75）：内容装饰标签。同样空表不下发。
            "decorationTags");

    /** 游标分页信封字段集 —— 对应 App FeedPage.fromJson 的 {items, nextCursor, hasMore}。 */
    /** V1.1.6 Story 16.5 起信封多一个 {@code rankMode}（非 Feed 出口时省略）。 */
    private static final Set<String> ENVELOPE_FIELDS =
            Set.of("items", "nextCursor", "hasMore", "rankMode");

    private Map<String, Object> wire(Object dto) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = json.convertValue(dto, Map.class);
        return m;
    }

    @Test
    void feedItemFullShapeHasExactlyTheContractFields() {
        FeedItemResponse item = new FeedItemResponse(
                42L, 7L, "小明", "https://cdn.petgo/p/a.jpg", false,
                // V1.1.6 Story 5.1：运营标签随作者一起下发；**空表不下发**（NON_NULL 省略）。
                List.of(new com.tailtopia.auth.dto.UserTagView("vet", "兽医", "🩺", "已认证兽医", "#F6A609")),
                // V1.1.6 Story 5.2：内容装饰标签；同样**空表不下发**。
                List.of(new ContentTagView("editor_pick", "编辑推荐", "🏆", "被官方选中的优质内容")),
                ContentType.DAILY, "今天带毛孩子去遛弯", "https://cdn.petgo/p/img.jpg", 3L,
                Instant.parse("2026-06-05T00:00:00Z"), ContentVisibility.PUBLIC,
                List.of("https://cdn.petgo/p/img.jpg", "https://cdn.petgo/p/img2.jpg"),
                // ⚠️ 用 Arrays.asList 而不是 List.of —— 后者不接受 null，
                // 而尺寸列**恰恰要用 null 占位保持与图片列的下标对齐**（AD-5 Rule 2）。
                Arrays.asList(new ImageSize(1200, 1600), null),
                true, 5L);

        Map<String, Object> m = wire(item);
        assertThat(m.keySet())
                .as("FeedItemResponse 对外字段集必须与 App FeedItem.fromJson 完全一致（C5）")
                .isEqualTo(ITEM_FIELDS);
        // 枚举落 UPPER_SNAKE 线格式（App FeedCategory.wire / FeedItem.type 依赖此）。
        assertThat(m.get("type")).isEqualTo("DAILY");
        // Story 4.1：visibility 为**必填、恒下发**（NON_NULL 下也不省略），线格式 UPPER_SNAKE。
        assertThat(m.get("visibility")).isEqualTo("PUBLIC");
    }

    @Test
    void deletedAuthorAndTextlessCardOmitsNullablesButKeepsRequired() {
        // 注销作者（NFR-8：昵称/头像不外泄）+ 纯文字无图卡（firstImageUrl null）+ 无正文。
        FeedItemResponse item = new FeedItemResponse(
                42L, 7L, null, null, true, null, null,
                ContentType.GROWTH_MOMENT, null, null, 0L,
                Instant.parse("2026-06-05T00:00:00Z"), ContentVisibility.PRIVATE,
                null, null, false, 0L);

        Map<String, Object> m = wire(item);
        assertThat(m.keySet())
                .as("NON_NULL：可空字段缺省即省略；必填恒在（App 侧均 null 容忍）")
                // V1.1.6 Story 3.1：liked / commentCount 是原始类型，**恒下发**（NON_NULL 不省略）；
                // imageUrls / imageSizes 可空 → 无图卡里应被省略。
                .isEqualTo(Set.of("id", "authorId", "authorDeleted", "type", "likeCount", "createdAt",
                        "visibility", "liked", "commentCount"));
        assertThat(m).doesNotContainKey("authorNickname"); // 注销不外泄 PII
        assertThat(m).doesNotContainKey("firstImageUrl");   // 纯文字卡，App hasImage=false
        assertThat(m).doesNotContainKey("imageUrls");        // 同上：无图即整列省略
        assertThat(m).doesNotContainKey("imageSizes");       // 存量内容亦然（零回填）
    }

    @Test
    void envelopeShapeMatchesCursorContractWhenHasMore() {
        FeedPageResponse page = new FeedPageResponse(List.of(), "eyJjcmVhdGVkQXQiOjF9", true,
                FeedPageResponse.RANK_MODE_RECOMMEND);

        Map<String, Object> m = wire(page);
        assertThat(m.keySet()).isEqualTo(ENVELOPE_FIELDS);
        assertThat(m.get("items")).isInstanceOf(List.class);
        assertThat(m.get("hasMore")).isEqualTo(true);
        // V1.1.6 Story 16.5：客户端靠它区分两条排序路径（降级时 ALL Tab 也是 chrono）。
        assertThat(m.get("rankMode")).isEqualTo("recommend");
    }

    /**
     * 🔴 非 Feed 出口（「我的发布」）不下发 {@code rankMode}。
     *
     * <p>给它填个 {@code chrono} 会让埋点侧把它算进首页排序的分母里。
     */
    @Test
    void envelopeOmitsRankModeForNonFeedOutlets() {
        FeedPageResponse page = new FeedPageResponse(List.of(), null, false, null);

        assertThat(wire(page)).doesNotContainKey("rankMode");
    }

    @Test
    void envelopeOmitsNextCursorOnLastPage() {
        // 末页：hasMore=false → nextCursor=null → NON_NULL 省略（App FeedPage.fromJson 容忍缺省）。
        FeedPageResponse page = new FeedPageResponse(List.of(), null, false,
                FeedPageResponse.RANK_MODE_CHRONO);

        Map<String, Object> m = wire(page);
        assertThat(m).doesNotContainKey("nextCursor");
        assertThat(m.keySet()).isEqualTo(Set.of("items", "hasMore", "rankMode"));
    }
}

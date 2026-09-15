package com.tailtopia.place.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标（CROSS-STORY C4/C5）：钉死场所列表对外 JSON 形状
 * （V1.3.0 batch-b1 Story 1.1 · FR-112.2 · AD-1）。
 *
 * <p><b>三处必须同步，任一漂移即契约破坏（本测试会红）：</b>
 * <ul>
 *   <li>后端 —— {@link PlaceListResponse} / {@link PlaceListItemResponse}</li>
 *   <li>App  —— {@code petgo_app/lib/features/place/domain/place_summary.dart}</li>
 *   <li>App 线上契约测试 —— {@code petgo_app/test/place/place_wire_contract_test.dart}</li>
 * </ul>
 * （{@code mock_backend.dart} 在当前代码里已不存在，C5 原文的第三处等价为上面那条线上契约测试。）
 *
 * <p>护栏：对外只有不可枚举 {@code token} —— 响应<b>不得含任何自增 DB id 字段</b>（NFR-1）。
 * 纯 Jackson 序列化、无 Spring/DB → 云端 headless 可跑（L0）。镜像生产 NON_NULL 配置
 * （{@code application.yml: jackson.default-property-inclusion=non_null}）。
 */
class PlaceListResponseContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static final Set<String> ITEM_FIELDS_FULL = Set.of(
            "token", "name", "type", "tags", "firstPhotoUrl", "photoCount",
            "distanceMeters", "commentCount", "recommendCount", "notRecommendCount");

    private static final Set<String> LIST_FIELDS = Set.of("items", "sortMode");

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    private static PlaceListItemResponse item(String photoUrl, Integer distanceMeters) {
        return new PlaceListItemResponse(
                "aZ09aZ09aZ09aZ09aZ09aZ09aZ09aZ09",
                "Kopi Anjing Senopati",
                PlaceType.CAFE,
                List.of(PlaceTag.PETS_ALLOWED_INSIDE, PlaceTag.OUTDOOR_SEATING),
                photoUrl,
                3,
                distanceMeters,
                0L, 0L, 0L);
    }

    @Test
    void itemWithDistanceHasExactlyContractFieldsWithUpperSnakeEnums() {
        Map<String, Object> m = wire(item("https://cdn/x.jpg", 820));

        assertThat(m.keySet()).isEqualTo(ITEM_FIELDS_FULL);
        assertThat(m.get("type")).isEqualTo("CAFE");
        assertThat(m.get("tags")).isEqualTo(List.of("PETS_ALLOWED_INSIDE", "OUTDOOR_SEATING"));
        assertThat(m.get("distanceMeters")).isEqualTo(820);
        // 对外只有 token，绝不外露自增 id（AD-1 Rule 3）。
        assertThat(m).doesNotContainKey("id");
        assertThat(m).doesNotContainKey("placeId");
    }

    /**
     * 「按最新」分支（Story 1.1 的默认路径）：距离位 null → NON_NULL 省略。
     *
     * <p>🔴 <b>省略而不是 0</b>：0 米是「就在脚下」，不是「不知道」。客户端读不到这个键时
     * 隐藏距离位；若服务端哪天改成填 0，列表上每个场所都会显示「0 m」。
     */
    @Test
    void recentBranchOmitsDistanceInsteadOfSendingZero() {
        Map<String, Object> m = wire(item("https://cdn/x.jpg", null));

        assertThat(m).doesNotContainKey("distanceMeters");
        assertThat(m.keySet()).isEqualTo(Set.of(
                "token", "name", "type", "tags", "firstPhotoUrl", "photoCount",
                "commentCount", "recommendCount", "notRecommendCount"));
    }

    /** 无照片的场所：首图省略，但 photoCount 仍恒下发（0 是有意义的值，不是缺失）。 */
    @Test
    void itemWithoutPhotoOmitsFirstPhotoUrlButKeepsPhotoCount() {
        PlaceListItemResponse noPhoto = new PlaceListItemResponse(
                "aZ09aZ09aZ09aZ09aZ09aZ09aZ09aZ09", "Taman Kota", PlaceType.PARK,
                List.of(PlaceTag.LEASH_REQUIRED), null, 0, null, 0L, 0L, 0L);

        Map<String, Object> m = wire(noPhoto);
        assertThat(m).doesNotContainKey("firstPhotoUrl");
        assertThat(m.get("photoCount")).isEqualTo(0);
    }

    /**
     * Story 1.1 的三个计数恒为 0，但**字段必须在**（契约先定，Story 1.7/1.8 接真值）。
     *
     * <p>⚠️ 这条用例守的是「不要因为现在恒 0 就把字段省掉」—— 省掉的话 1.8 上线时
     * 客户端得再改一次 DTO，而中间那个版本的客户端会把计数当作不存在。
     */
    @Test
    void zeroCountsAreStillSerialized() {
        Map<String, Object> m = wire(item(null, null));

        // 三个计数是 long（线格式为 JSON number）—— 断言写 0L，写 0 会因 Integer/Long 类型不等而红。
        assertThat(m.get("commentCount")).isEqualTo(0L);
        assertThat(m.get("recommendCount")).isEqualTo(0L);
        assertThat(m.get("notRecommendCount")).isEqualTo(0L);
    }

    @Test
    void listEnvelopeShapeMatchesContract() {
        PlaceListResponse resp = PlaceListResponse.recent(List.of(item("https://cdn/x.jpg", null)));

        Map<String, Object> m = wire(resp);
        assertThat(m.keySet()).isEqualTo(LIST_FIELDS);
        assertThat(m.get("sortMode")).isEqualTo("recent");
        assertThat(m.get("items")).isInstanceOf(List.class);
    }

    /** 空库（冷启动前）：items 为空数组而不是省略 —— 客户端据此走空态而不是错误态。 */
    @Test
    void emptyListStillSendsItemsArray() {
        Map<String, Object> m = wire(PlaceListResponse.recent(List.of()));

        assertThat(m.keySet()).isEqualTo(LIST_FIELDS);
        assertThat(m.get("items")).isEqualTo(List.of());
    }
}

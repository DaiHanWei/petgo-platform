package com.tailtopia.place.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标（CROSS-STORY C4/C5）：场所详情对外 JSON 形状
 * （V1.3.0 batch-b1 Story 1.5 · AC1/AC6）。
 *
 * <h2>🔴 这份测试有一半是在断言「没有什么」</h2>
 * FR-112.6 / AC6 的反向验收（没有收藏、没有评分、没有营业时间电话、没有打卡、没有编辑入口）
 * 在界面上是 L2，但**在契约上是 L0 可证伪的**：字段不存在，界面就不可能画出来。
 * 而"不小心加一个字段"比"不小心画一个按钮"容易得多 —— 那才是这组断言存在的理由。
 */
class PlaceDetailResponseContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static final Set<String> FULL_FIELDS = Set.of(
            "token", "name", "type", "tags", "photoUrls", "addressText", "description",
            "latitude", "longitude", "distanceMeters", "markedBy",
            "commentCount", "recommendCount", "notRecommendCount");

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    private static Place place() {
        return Place.mark("aZ09aZ09aZ09aZ09aZ09aZ09aZ09aZ09", "Kopi Kayu Manis", PlaceType.CAFE,
                List.of(PlaceTag.PETS_ALLOWED_INSIDE, PlaceTag.OUTDOOR_SEATING),
                -6.235, 106.81, "Jl. Senopati No.75", "Ada area outdoor",
                List.of("https://cdn/a.jpg", "https://cdn/b.jpg"), 7L);
    }

    private static AuthorView marker() {
        return new AuthorView(7L, "Rani", "https://cdn/avatar.jpg", false, List.of());
    }

    @Test
    void detailHasExactlyContractFields() {
        Map<String, Object> m =
                wire(PlaceDetailResponse.of(place(), marker(), 1200, 3L, 11L, 2L));

        assertThat(m.keySet()).isEqualTo(FULL_FIELDS);
        assertThat(m.get("type")).isEqualTo("CAFE");
        assertThat(m.get("distanceMeters")).isEqualTo(1200);
        assertThat(m.get("latitude")).isEqualTo(-6.235);
        assertThat(m.get("longitude")).isEqualTo(106.81);
    }

    /** 🔴 对外只有 token，绝不外露自增 id（NFR-1 / AD-1 Rule 3）。 */
    @Test
    void detailNeverLeaksDatabaseIds() {
        Map<String, Object> m = wire(PlaceDetailResponse.of(place(), marker(), null, 0L, 0L, 0L));

        assertThat(m).doesNotContainKey("id");
        assertThat(m).doesNotContainKey("placeId");
        assertThat(m).doesNotContainKey("createdBy");
    }

    /**
     * 🔴 **AC6 反向验收**：这些字段一个都不许有。
     *
     * <p>不是「还没做」，是**明确不做**（PRD ⑥ / FR-112.6 / 2026-09-15 拍板）。
     * 往 DTO 里加任何一条之前先回 PRD 改口径 —— 这条测试会先红。
     */
    @Test
    void detailHasNoneOfTheExplicitlyExcludedFields() {
        Map<String, Object> m = wire(PlaceDetailResponse.of(place(), marker(), 1200, 3L, 11L, 2L));

        for (String excluded : List.of(
                // 收藏
                "favorited", "favorite", "favoriteCount", "bookmarked",
                // 评分打星
                "rating", "ratingAvg", "score", "stars",
                // 营业时间 / 电话等商户字段
                "openingHours", "businessHours", "phone", "phoneNumber", "website",
                // ⑧ 打卡（批次 B2）
                "checkedIn", "checkinCount", "checkInCount", "canCheckIn",
                // 用户不可编辑场所
                "editable", "canEdit", "editUrl")) {
            assertThat(m).as("AC6 反向验收：不该有 %s", excluded).doesNotContainKey(excluded);
        }
    }

    /** 「按最新」进来的详情没有距离 → 省略（不是 0）。 */
    @Test
    void detailWithoutCoordinatesOmitsDistance() {
        Map<String, Object> m = wire(PlaceDetailResponse.of(place(), marker(), null, 0L, 0L, 0L));

        assertThat(m).doesNotContainKey("distanceMeters");
    }

    /** 🔴 照片一律经服务端去 EXIF（E4）—— 场所照片进的是公开桶，URL 下发给所有人。 */
    @Test
    void photosAreExifStrippedOnDelivery() {
        PlaceDetailResponse r = PlaceDetailResponse.of(place(), marker(), null, 0L, 0L, 0L);

        assertThat(r.photoUrls()).hasSize(2);
        assertThat(r.photoUrls()).allSatisfy(u -> assertThat(u)
                .contains("x-oss-process=image/")
                .contains("format,jpg")
                .contains("resize,w_" + PlaceDetailResponse.DETAIL_PHOTO_WIDTH_PX));
    }

    /** 标记人已注销 → 昵称/头像为 null（NFR-8 匿名化），但 userId 仍在（前端据 deleted 决定可点性）。 */
    @Test
    void deletedMarkerIsAnonymized() {
        Map<String, Object> m = wire(
                PlaceDetailResponse.of(place(), AuthorView.anonymized(7L), null, 0L, 0L, 0L));

        @SuppressWarnings("unchecked")
        Map<String, Object> markedBy = (Map<String, Object>) m.get("markedBy");
        assertThat(markedBy.get("deleted")).isEqualTo(true);
        assertThat(markedBy).doesNotContainKey("nickname");
        assertThat(markedBy).doesNotContainKey("avatarUrl");
        // userId 是 long → convertValue 给 Long（不是 Integer）。
        assertThat(markedBy.get("userId")).isEqualTo(7L);
    }

    /** 无描述时省略该键（NON_NULL）；无照片时是空表而不是 null。 */
    @Test
    void optionalFieldsBehaveAsContracted() {
        Place noExtras = Place.mark("t".repeat(32), "Taman", PlaceType.PARK,
                List.of(PlaceTag.LEASH_REQUIRED), -6.2, 106.8, "Jl. A", null, List.of(), 7L);

        Map<String, Object> m = wire(PlaceDetailResponse.of(noExtras, marker(), null, 0L, 0L, 0L));
        assertThat(m).doesNotContainKey("description");
        assertThat(m.get("photoUrls")).isEqualTo(List.of());
    }
}

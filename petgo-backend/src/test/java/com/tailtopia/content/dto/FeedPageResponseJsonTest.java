package com.tailtopia.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0：关注流首页两个计数（V1.4.2 social-topic Story 1.3 · AC3 · D-9 / P-5）。
 *
 * <p>只在关注流首页下发 {@code followingUserCount} / {@code followingPetCount}；
 * 4 参兼容构造器（Discover / 我的发布 / 关注流后续页）两个计数为 null → NON_NULL 不下发。
 */
class FeedPageResponseJsonTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void fourArgConstructorLeavesBothCountsNull() {
        FeedPageResponse page = new FeedPageResponse(List.of(), null, false, FeedPageResponse.RANK_MODE_CHRONO);
        assertThat(page.followingUserCount()).isNull();
        assertThat(page.followingPetCount()).isNull();
        assertThat(wire(page)).doesNotContainKeys("followingUserCount", "followingPetCount");
        assertThat(wire(page).keySet()).isEqualTo(Set.of("items", "hasMore", "rankMode"));
    }

    @Test
    void followingFeedFirstPageSerializesBothCounts() {
        FeedPageResponse page = new FeedPageResponse(List.of(), null, false,
                FeedPageResponse.RANK_MODE_CHRONO, 1L, 0L);
        Map<String, Object> m = wire(page);
        // 0 也要下发（App 据「两者都为 0」判现有空态）。
        assertThat(((Number) m.get("followingUserCount")).longValue()).isEqualTo(1L);
        assertThat(((Number) m.get("followingPetCount")).longValue()).isZero();
    }

    @Test
    void countsAreAppendedAfterRankMode() {
        var comps = FeedPageResponse.class.getRecordComponents();
        assertThat(comps).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("items", "nextCursor", "hasMore", "rankMode",
                        "followingUserCount", "followingPetCount");
    }
}

package com.tailtopia.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0：关注流 {@code reason}（V1.4.2 social-topic Story 1.3 · AC3 · D-9 / P-5）。
 *
 * <p>只在关注流出口经 {@link FeedItemResponse#withReason} 填值；共用工厂 {@link FeedItemResponse#of}
 * 产出的投影恒为 null（NON_NULL 下不下发 → Discover / 我的发布 / 他人主页 key 集合不变）。
 */
class FeedItemResponseJsonTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static FeedItemResponse sample() {
        ContentPost p = ContentPost.publish(7L, ContentType.GROWTH_MOMENT, null, "Mochi hari ini", List.of());
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
        AuthorView author = new AuthorView(7L, "u7", null, false, List.of());
        return FeedItemResponse.of(p, author, 0L, false, 0L, null, null, "t".repeat(32),
                com.tailtopia.social.read.FollowState.NONE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void sharedFactoryLeavesReasonNullAndOmitsTheKey() {
        FeedItemResponse item = sample();
        assertThat(item.reason()).isNull();
        assertThat(wire(item)).doesNotContainKey("reason");
    }

    @Test
    void followedUserAndFollowedPetAreSerializedVerbatim() {
        assertThat(wire(sample().withReason(FeedItemResponse.REASON_FOLLOWED_USER)))
                .containsEntry("reason", "followed_user");
        assertThat(wire(sample().withReason(FeedItemResponse.REASON_FOLLOWED_PET)))
                .containsEntry("reason", "followed_pet");
    }

    @Test
    void withReasonChangesOnlyTheReasonColumn() {
        FeedItemResponse base = sample();
        FeedItemResponse withReason = base.withReason("followed_pet");
        // 同一个投影：除 reason 外逐字段相等（不是另写一个工厂）。
        assertThat(withReason.withReason(null)).isEqualTo(base);
        Map<String, Object> a = wire(base);
        Map<String, Object> b = wire(withReason);
        b.remove("reason");
        assertThat(b).isEqualTo(a);
    }

    @Test
    void reasonIsTheLastRecordComponent() {
        // 末尾追加，不打乱既有字段顺序。
        var comps = FeedItemResponse.class.getRecordComponents();
        assertThat(comps[comps.length - 1].getName()).isEqualTo("reason");
        assertThat(comps[comps.length - 2].getName()).isEqualTo("authorFrame");
    }
}

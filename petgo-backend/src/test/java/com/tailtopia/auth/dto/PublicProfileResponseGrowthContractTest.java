package com.tailtopia.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthBadge;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.social.read.FollowState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** V1.4.0 ① Story 3.1：他人主页加 level / tier / frame；无等级与 {@code deactivated()} 时省略。 */
class PublicProfileResponseGrowthContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    private static PublicProfileResponse of(AuthorView author) {
        return PublicProfileResponse.of(author, "hi", Instant.parse("2026-01-01T00:00:00Z"), 3, 4, false, null,
                "t".repeat(32), 1, 2, FollowState.NONE, false);
    }

    @Test
    void withGrowth() {
        AuthorView a = new AuthorView(7L, "Rina", null, false, List.of())
                .withGrowth(new GrowthBadge(12, GrowthTier.GUARDIAN, FrameChoice.UPGRADED));
        assertThat(wire(of(a))).containsEntry("level", 12).containsEntry("tier", "GUARDIAN")
                .containsEntry("frame", "UPGRADED");
    }

    @Test
    void withoutGrowthAndDeactivated() {
        assertThat(wire(of(new AuthorView(7L, "Rina", null, false, List.of()))))
                .doesNotContainKeys("level", "tier", "frame");
        assertThat(wire(PublicProfileResponse.deactivated())).doesNotContainKeys("level", "tier", "frame");
    }
}

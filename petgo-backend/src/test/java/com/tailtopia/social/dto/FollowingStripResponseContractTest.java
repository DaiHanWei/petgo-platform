package com.tailtopia.social.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** V1.4.0 ① Story 3.1：关注头像条每项加 level / tier / frame（无等级 / 无框时省略）。 */
class FollowingStripResponseContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wireItem(FollowingStripResponse.Item item) {
        Map<String, Object> page = json.convertValue(new FollowingStripResponse(List.of(item)), Map.class);
        return ((List<Map<String, Object>>) page.get("items")).get(0);
    }

    @Test
    void withGrowth() {
        assertThat(wireItem(new FollowingStripResponse.Item(7L, "t".repeat(32), "Rina", "https://cdn/a.jpg",
                10, "GUARDIAN", "UPGRADED")).keySet())
                .isEqualTo(Set.of("userId", "userToken", "nickname", "avatarUrl", "level", "tier", "frame"));
    }

    @Test
    void withoutGrowth() {
        assertThat(wireItem(new FollowingStripResponse.Item(7L, "t".repeat(32), "Rina", null, null, null, null)))
                .doesNotContainKeys("level", "tier", "frame");
    }
}

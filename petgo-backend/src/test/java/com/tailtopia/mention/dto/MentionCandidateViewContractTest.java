package com.tailtopia.mention.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** V1.4.0 ① Story 3.1：@ 候选加 level / tier / frame（无等级 / 无框时省略）。 */
class MentionCandidateViewContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void withGrowth() {
        assertThat(wire(new MentionCandidateView(7L, "Rina", null, 5, "BUDDY", "BASIC")).keySet())
                .isEqualTo(Set.of("userId", "nickname", "level", "tier", "frame"));
    }

    @Test
    void withoutGrowth() {
        assertThat(wire(new MentionCandidateView(7L, "Rina", "https://cdn/a.jpg", null, null, null)).keySet())
                .isEqualTo(Set.of("userId", "nickname", "avatarUrl"));
    }
}

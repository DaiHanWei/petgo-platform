package com.tailtopia.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** V1.4.0 ① Story 3.1：公开分享投影加作者等级三字段，仍不带任何 id（AD-15 Rule 5）。 */
class SharedPostResponseContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void withGrowth() {
        SharedPostResponse r = new SharedPostResponse("Rina", "https://cdn/a.jpg", false, "DAILY", "正文",
                List.of("https://cdn/1.jpg"), Instant.parse("2026-10-18T00:00:00Z"), 7, "BUDDY", "BASIC");
        assertThat(wire(r).keySet()).isEqualTo(Set.of("authorNickname", "authorAvatarUrl", "authorDeleted", "type",
                "body", "imageUrls", "createdAt", "authorLevel", "authorTier", "authorFrame"));
    }

    @Test
    void withoutGrowth() {
        SharedPostResponse r = new SharedPostResponse(null, null, true, "DAILY", null, List.of(),
                Instant.parse("2026-10-18T00:00:00Z"), null, null, null);
        assertThat(wire(r)).doesNotContainKeys("authorLevel", "authorTier", "authorFrame");
    }
}

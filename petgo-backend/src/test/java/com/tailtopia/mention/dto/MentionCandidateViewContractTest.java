package com.tailtopia.mention.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.mention.domain.MentionRelation;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * V1.4.0 ① Story 3.1：@ 候选加 level / tier / frame（无等级 / 无框时省略）。
 * V1.4.2 social-topic Story 5.1：末尾加 {@code relation}（小写 wire，P-5）；既有字段原样（NFR7）。
 */
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
        assertThat(wire(new MentionCandidateView(7L, "Rina", null, 5, "BUDDY", "BASIC",
                MentionRelation.FOLLOWING)).keySet())
                .isEqualTo(Set.of("userId", "nickname", "level", "tier", "frame", "relation"));
    }

    @Test
    void withoutGrowth() {
        assertThat(wire(new MentionCandidateView(7L, "Rina", "https://cdn/a.jpg", null, null, null,
                MentionRelation.INTERACTED)).keySet())
                .isEqualTo(Set.of("userId", "nickname", "avatarUrl", "relation"));
    }

    @Test
    void relationWireValuesAreLowercase() {
        assertThat(wire(view(MentionRelation.FOLLOWING)).get("relation")).isEqualTo("following");
        assertThat(wire(view(MentionRelation.FOLLOWER)).get("relation")).isEqualTo("follower");
        assertThat(wire(view(MentionRelation.INTERACTED)).get("relation")).isEqualTo("interacted");
    }

    @Test
    void relationDeclarationOrderIsPriorityAndMatchesSqlRank() {
        // 声明顺序 = 优先级（following > follower > interacted），rank 与 MentionScopeDao SQL 的 rk 一一对应。
        assertThat(MentionRelation.values()).containsExactly(
                MentionRelation.FOLLOWING, MentionRelation.FOLLOWER, MentionRelation.INTERACTED);
        assertThat(MentionRelation.ofRank(1)).isEqualTo(MentionRelation.FOLLOWING);
        assertThat(MentionRelation.ofRank(2)).isEqualTo(MentionRelation.FOLLOWER);
        assertThat(MentionRelation.ofRank(3)).isEqualTo(MentionRelation.INTERACTED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MentionRelation.ofRank(4))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MentionCandidateView view(MentionRelation r) {
        return new MentionCandidateView(7L, "Rina", null, null, null, null, r);
    }
}

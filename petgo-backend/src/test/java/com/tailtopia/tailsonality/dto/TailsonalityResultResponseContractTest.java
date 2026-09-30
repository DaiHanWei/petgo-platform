package com.tailtopia.tailsonality.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标（V1.3.2 Story 2.1 · AC6）：结果 DTO 对外 JSON 形状。
 *
 * <p>App 侧 {@code test/tailsonality/tailsonality_result_wire_contract_test.dart} 的 fixture 与这里的
 * {@link #FULL_FIELDS} 同集 —— 改任一侧必须同步另一侧。
 */
class TailsonalityResultResponseContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    static final Set<String> FULL_FIELDS = Set.of(
            "token", "typeCode", "letters", "energy", "questionSet", "resultIndex",
            "unlocked", "unlockedAt", "contentVersion", "createdAt",
            // V1.3.2 Story 3.3：是否正被佩戴。
            "equipped");

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void unlockedResultHasExactlyContractFields() {
        var dto = new TailsonalityResultResponse("t".repeat(32), "ENTJ-H", "ENTJ", "H", "CAT", 2, true,
                Instant.parse("2026-09-30T09:00:00Z"), 1, Instant.parse("2026-09-30T08:00:00Z"), true);
        Map<String, Object> m = wire(dto);
        assertThat(m.keySet()).isEqualTo(FULL_FIELDS);
        assertThat(m.get("typeCode")).isEqualTo("ENTJ-H");
        assertThat(m.get("resultIndex")).isEqualTo(2);
        assertThat(m.get("equipped")).isEqualTo(true);
    }

    @Test
    void lockedResultOmitsUnlockedAtAndNeverCarriesAnswersOrScores() {
        var dto = new TailsonalityResultResponse("t".repeat(32), "ISTP-L", "ISTP", "L", "GENERAL", 1, false,
                null, 1, Instant.parse("2026-09-30T08:00:00Z"), false);
        Map<String, Object> m = wire(dto);
        assertThat(m).doesNotContainKey("unlockedAt");
        assertThat(m.get("unlocked")).isEqualTo(false);
        assertThat(m.keySet()).doesNotContain("answers", "weights", "scores", "axisScores", "id");
    }
}

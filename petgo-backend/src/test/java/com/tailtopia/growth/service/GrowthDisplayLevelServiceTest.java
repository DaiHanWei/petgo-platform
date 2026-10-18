package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** V1.4.0 ① Story 2.2 · AC4（L0）：随机源 0 / 1 / 2 → 展示等级 6 / 7 / 8；只插不改。 */
class GrowthDisplayLevelServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void randomMapsToSixToEightAndNeverOverwrites() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        Deque<Integer> rolls = new ArrayDeque<>(List.of(0, 1, 2));
        GrowthDisplayLevelService svc = new GrowthDisplayLevelService(jdbc, rolls::pop);

        svc.assignInitial(1L);
        svc.assignInitial(2L);
        svc.assignInitial(3L);

        ArgumentCaptor<Map<String, ?>> p = ArgumentCaptor.forClass(Map.class);
        verify(jdbc, org.mockito.Mockito.times(3)).update(eq(GrowthDisplayLevelService.ASSIGN_SQL), p.capture());
        assertThat(p.getAllValues().stream().map(m -> (Object) m.get("d")).toList()).containsExactly(6, 7, 8);
        assertThat(GrowthDisplayLevelService.ASSIGN_SQL).contains("ON CONFLICT (user_id) DO NOTHING");
    }
}

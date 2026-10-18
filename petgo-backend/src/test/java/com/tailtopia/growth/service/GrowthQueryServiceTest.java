package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** V1.4.0 Story 0.1 · AC3（L0）：占位实现 —— 真实用户 Lv1/NEWBIE，虚拟号与查无此人「不参与」，批量一次查询、键齐全。 */
class GrowthQueryServiceTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GrowthQueryService service = new GrowthQueryService(jdbc);

    private void givenUsers(List<Map<String, Object>> rows) {
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(rows);
    }

    @Test
    void realUserIsLevelOneNewbie() {
        givenUsers(List.of(Map.of("id", 1L, "account_type", "REAL")));

        GrowthLevel level = service.getLevel(1L);

        assertThat(level.participating()).isTrue();
        assertThat(level.level()).isEqualTo(1);
        assertThat(level.tier()).isEqualTo(GrowthTier.NEWBIE);
    }

    @Test
    void virtualUserDoesNotParticipate() {
        givenUsers(List.of(Map.of("id", 2L, "account_type", "VIRTUAL")));

        assertThat(service.getLevel(2L)).isEqualTo(GrowthLevel.NOT_PARTICIPATING);
    }

    @Test
    void missingUserDoesNotParticipate() {
        givenUsers(List.of());

        assertThat(service.getLevel(404L)).isEqualTo(GrowthLevel.NOT_PARTICIPATING);
    }

    @Test
    void batchCoversEveryRequestedIdWithOneQuery() {
        givenUsers(List.of(
                Map.of("id", 1L, "account_type", "REAL"),
                Map.of("id", 2L, "account_type", "VIRTUAL")));

        Map<Long, GrowthLevel> levels = service.getLevels(List.of(1L, 2L, 404L));

        assertThat(levels).containsOnlyKeys(1L, 2L, 404L);
        assertThat(levels.get(1L)).isEqualTo(GrowthLevel.of(1, GrowthTier.NEWBIE));
        assertThat(levels.get(2L)).isEqualTo(GrowthLevel.NOT_PARTICIPATING);
        assertThat(levels.get(404L)).isEqualTo(GrowthLevel.NOT_PARTICIPATING);
        verify(jdbc, times(1)).queryForList(anyString(), anyMap());
    }

    @Test
    void emptyBatchSkipsTheQuery() {
        assertThat(service.getLevels(List.of())).isEmpty();
        verify(jdbc, never()).queryForList(anyString(), anyMap());
    }
}

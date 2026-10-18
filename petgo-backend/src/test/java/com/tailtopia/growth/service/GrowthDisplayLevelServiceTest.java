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

    // ------------------------------------------------------------------ Story 4.4

    private static NamedParameterJdbcTemplate jdbcWithMax(int max, Integer current) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForObject(eq(GrowthDisplayLevelService.MAX_LEVEL_SQL),
                org.mockito.ArgumentMatchers.<Map<String, ?>>any(), eq(Integer.class))).thenReturn(max);
        org.mockito.Mockito.when(jdbc.queryForList(eq(GrowthDisplayLevelService.CURRENT_SQL),
                org.mockito.ArgumentMatchers.<Map<String, ?>>any(), eq(Integer.class)))
                .thenReturn(current == null ? List.of() : List.of(current));
        return jdbc;
    }

    @Test
    @SuppressWarnings("unchecked")
    void setUpsertsOnlyDisplayLevel() {
        NamedParameterJdbcTemplate jdbc = jdbcWithMax(20, 7);
        GrowthDisplayLevelService svc = new GrowthDisplayLevelService(jdbc, () -> 0);

        GrowthDisplayLevelService.Change c = svc.set(5L, 12);

        assertThat(c).isEqualTo(new GrowthDisplayLevelService.Change(7, 12, true));
        ArgumentCaptor<Map<String, ?>> p = ArgumentCaptor.forClass(Map.class);
        verify(jdbc).update(eq(GrowthDisplayLevelService.SET_SQL), p.capture());
        assertThat(p.getValue().get("u")).isEqualTo(5L);
        assertThat(p.getValue().get("d")).isEqualTo(12);
        // 只改 display_level（与 updated_at）：冲突分支不碰累计 / 等级 / 待弹
        String onConflict = GrowthDisplayLevelService.SET_SQL.substring(GrowthDisplayLevelService.SET_SQL.indexOf("DO UPDATE"));
        assertThat(onConflict).contains("display_level = EXCLUDED.display_level")
                .doesNotContain("lifetime_total").doesNotContain(" level =").doesNotContain("celebrate");
    }

    @Test
    void unchangedValueDoesNotWrite() {
        NamedParameterJdbcTemplate jdbc = jdbcWithMax(20, 7);
        GrowthDisplayLevelService svc = new GrowthDisplayLevelService(jdbc, () -> 0);
        assertThat(svc.set(5L, 7).changed()).isFalse();
        verify(jdbc, org.mockito.Mockito.never()).update(eq(GrowthDisplayLevelService.SET_SQL),
                org.mockito.ArgumentMatchers.<Map<String, ?>>any());
    }

    @Test
    void outOfRangeIs422WithMax() {
        GrowthDisplayLevelService svc = new GrowthDisplayLevelService(jdbcWithMax(20, null), () -> 0);
        for (int bad : new int[] {0, -1, 21}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.set(5L, bad))
                    .satisfies(t -> {
                        com.tailtopia.shared.error.AppException e = (com.tailtopia.shared.error.AppException) t;
                        assertThat(e.getMessageCode()).isEqualTo("admin.err.growth.display.outOfRange");
                        assertThat(e.getMessageArgs()).containsExactly(20);
                    });
        }
        assertThat(svc.set(5L, 20).changed()).isTrue();
        assertThat(svc.set(6L, 1).oldLevel()).isNull();
    }

    @Test
    void displayLevelsBatchReadIsOneQuery() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForList(eq(GrowthDisplayLevelService.DISPLAY_LEVELS_SQL),
                org.mockito.ArgumentMatchers.<Map<String, ?>>any())).thenReturn(List.of(
                        Map.of("user_id", 1L, "display_level", 12, "tier", "GUARDIAN"),
                        Map.of("user_id", 2L, "display_level", 6, "tier", "BUDDY")));
        GrowthDisplayLevelService svc = new GrowthDisplayLevelService(jdbc, () -> 0);

        Map<Long, GrowthDisplayLevelService.DisplayLevel> m = svc.displayLevels(List.of(1L, 2L, 3L));

        assertThat(m).hasSize(2);
        assertThat(m.get(1L)).isEqualTo(new GrowthDisplayLevelService.DisplayLevel(12,
                com.tailtopia.growth.domain.GrowthTier.GUARDIAN));
        assertThat(m.get(3L)).isNull();
        verify(jdbc, org.mockito.Mockito.times(1)).queryForList(eq(GrowthDisplayLevelService.DISPLAY_LEVELS_SQL),
                org.mockito.ArgumentMatchers.<Map<String, ?>>any());
        assertThat(svc.displayLevels(List.of())).isEmpty();
    }
}

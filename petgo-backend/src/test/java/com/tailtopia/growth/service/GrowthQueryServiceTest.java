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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * V1.4.0 ① Story 1.1 · AC4（L0，改写地基 Story 0.1 测试）：读落库等级；虚拟号 / 官方号 / 查无此人 / 已注销 / 已停用
 * 「不参与」（参与判定在 SQL 里，此处以 {@code participating} 列模拟）；批量一次查询、键齐全。
 */
class GrowthQueryServiceTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GrowthQueryService service = new GrowthQueryService(jdbc);

    private void givenRows(List<Map<String, Object>> rows) {
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(rows);
    }

    private static Map<String, Object> row(long id, boolean participating, int level, String tier) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("participating", participating);
        m.put("level", level);
        m.put("tier", tier);
        return m;
    }

    @Test
    void realUserWithoutAccountIsLevelOneNewbie() {
        // 无账户行：SQL 里 COALESCE(a.level, 1) + 等级表 Lv1 = NEWBIE。
        givenRows(List.of(row(1L, true, 1, "NEWBIE")));

        GrowthLevel level = service.getLevel(1L);

        assertThat(level).isEqualTo(GrowthLevel.of(1, GrowthTier.NEWBIE));
    }

    @Test
    void accountRowLevelIsReadFromTheTable() {
        givenRows(List.of(row(1L, true, 15, "STAR")));

        assertThat(service.getLevel(1L)).isEqualTo(GrowthLevel.of(15, GrowthTier.STAR));
    }

    @Test
    void virtualOfficialDeletedOrDeactivatedDoNotParticipate() {
        // VIRTUAL / role=ADMIN / deleted_at / status=DEACTIVATED 都让 participating 列为 false。
        givenRows(List.of(row(2L, false, 8, "BUDDY")));

        assertThat(service.getLevel(2L)).isEqualTo(GrowthLevel.NOT_PARTICIPATING);
    }

    @Test
    void missingUserDoesNotParticipate() {
        givenRows(List.of());

        assertThat(service.getLevel(404L)).isEqualTo(GrowthLevel.NOT_PARTICIPATING);
    }

    @Test
    void participationPredicateMatchesActiveRegularUser() {
        assertThat(GrowthQueryService.LEVELS_SQL)
                .contains("u.role = 'USER'")
                .contains("u.account_type = 'REAL'")
                .contains("u.deleted_at IS NULL")
                .contains("u.status = 'ACTIVE'")
                .contains("LEFT JOIN growth_accounts")
                .contains("LEFT JOIN growth_levels");
    }

    @Test
    void batchCoversEveryRequestedIdWithOneQuery() {
        givenRows(List.of(row(1L, true, 5, "BUDDY"), row(2L, false, 1, "NEWBIE")));

        Map<Long, GrowthLevel> levels = service.getLevels(List.of(1L, 2L, 404L));

        assertThat(levels).containsOnlyKeys(1L, 2L, 404L);
        assertThat(levels.get(1L)).isEqualTo(GrowthLevel.of(5, GrowthTier.BUDDY));
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

package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthBadge;
import com.tailtopia.growth.domain.GrowthTier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * V1.4.0 ① Story 3.1 · AC1（L0）：作者等级批量展示查询。过滤（已注销 / 已停用 / 其他角色 / 查无此人）与等级、段位取值在
 * SQL 里（此处以行模拟、并断言 SQL 口径）；Java 侧负责框推导、虚拟号 / 官方号取最高档、空入参与查询次数。
 */
class GrowthQueryServiceBadgesTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GrowthQueryService service = new GrowthQueryService(jdbc);

    private static Map<String, Object> row(long id, boolean realUser, int level, String tier, String frameChoice,
            boolean displayMissing) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("real_user", realUser);
        m.put("level", level);
        m.put("tier", tier);
        m.put("frame_choice", frameChoice);
        m.put("display_missing", displayMissing);
        return m;
    }

    private Map<Long, GrowthBadge> badges(List<Long> ids, List<Map<String, Object>> rows) {
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(rows);
        return service.getBadges(ids);
    }

    @Test
    void realUsers() {
        Map<Long, GrowthBadge> b = badges(List.of(1L, 2L, 3L, 4L, 5L), List.of(
                row(1L, true, 1, "NEWBIE", null, false), // 无账户行（SQL COALESCE 成 Lv1）
                row(2L, true, 12, "GUARDIAN", null, false), // 自动 → 最高可用档
                row(3L, true, 12, "GUARDIAN", "NONE", false), // 主动不戴
                row(4L, true, 7, "BUDDY", "UPGRADED", false), // 已选 UPGRADED 但已降到 BUDDY → 退回 BASIC
                row(5L, true, 15, "STAR", "BASIC", false))); // 已选且可用 → 它

        assertThat(b.get(1L)).isEqualTo(new GrowthBadge(1, GrowthTier.NEWBIE, null));
        assertThat(b.get(2L)).isEqualTo(new GrowthBadge(12, GrowthTier.GUARDIAN, FrameChoice.UPGRADED));
        assertThat(b.get(3L)).isEqualTo(new GrowthBadge(12, GrowthTier.GUARDIAN, null));
        assertThat(b.get(4L)).isEqualTo(new GrowthBadge(7, GrowthTier.BUDDY, FrameChoice.BASIC));
        assertThat(b.get(5L)).isEqualTo(new GrowthBadge(15, GrowthTier.STAR, FrameChoice.BASIC));
    }

    @Test
    void virtualAndOfficialUseDisplayLevelAndHighestFrameIgnoringChoice() {
        Map<Long, GrowthBadge> b = badges(List.of(10L, 11L, 12L), List.of(
                row(10L, false, 15, "STAR", "NONE", false), // 虚拟号：不看 frame_choice
                row(11L, false, 12, "GUARDIAN", null, false), // 官方号（P-7）
                row(12L, false, 1, "NEWBIE", null, true))); // display_level 为 NULL → Lv1 兜底

        assertThat(b.get(10L)).isEqualTo(new GrowthBadge(15, GrowthTier.STAR, FrameChoice.ANIMATED));
        assertThat(b.get(11L)).isEqualTo(new GrowthBadge(12, GrowthTier.GUARDIAN, FrameChoice.UPGRADED));
        assertThat(b.get(12L)).isEqualTo(new GrowthBadge(1, GrowthTier.NEWBIE, null));
    }

    @Test
    void filteredRowsAreAbsent() {
        // 已注销 / 已停用 / 兽医 / 查无此人：SQL 不返回 → 不在 Map。
        Map<Long, GrowthBadge> b = badges(List.of(1L, 20L, 21L, 22L), List.of(row(1L, true, 3, "NEWBIE", null, false)));
        assertThat(b).containsOnlyKeys(1L);
    }

    @Test
    void sqlEncodesTheParticipationAndTierRules() {
        String sql = GrowthQueryService.BADGES_SQL;
        assertThat(sql).contains("u.deleted_at IS NULL").contains("u.status = 'ACTIVE'")
                .contains("u.role = 'ADMIN'").contains("u.account_type IN ('REAL', 'VIRTUAL')")
                .contains("COALESCE(a.level, 1)").contains("COALESCE(a.display_level, 1)")
                // 段位一律取等级表，不按数字硬编码。
                .contains("FROM growth_levels l WHERE l.level <= b.level ORDER BY l.level DESC LIMIT 1");
    }

    @Test
    void emptyInputSendsNoQuery() {
        assertThat(service.getBadges(List.of())).isEmpty();
        verify(jdbc, never()).queryForList(anyString(), anyMap());
    }

    @Test
    void manyIdsOneQuery() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 50).boxed().toList();
        badges(ids, List.of());
        verify(jdbc, times(1)).queryForList(eq(GrowthQueryService.BADGES_SQL), anyMap());
    }
}

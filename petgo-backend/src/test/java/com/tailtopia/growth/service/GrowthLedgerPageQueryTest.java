package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.web.dto.GrowthLedgerPage;
import com.tailtopia.growth.web.dto.GrowthLedgerRow;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.paging.KeysetCursor;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** V1.4.0 ① Story 1.4 · AC3（L0）：detailCode 映射、reason / note 透传、分页与非法游标。 */
class GrowthLedgerPageQueryTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GrowthLedgerPageQuery query = new GrowthLedgerPageQuery(jdbc);

    private record R(long id, String rule, int delta, String reason, String note, String refType, String refId) {
    }

    @SuppressWarnings("unchecked")
    private void rows(List<R> rs) {
        when(jdbc.query(eq(GrowthLedgerPageQuery.PAGE_SQL), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<Object> m = inv.getArgument(2);
                    List<Object> out = new ArrayList<>();
                    int i = 0;
                    for (R r : rs) {
                        ResultSet set = mock(ResultSet.class);
                        when(set.getLong("id")).thenReturn(r.id);
                        when(set.getString("rule_code")).thenReturn(r.rule);
                        when(set.getInt("delta")).thenReturn(r.delta);
                        when(set.getString("reason")).thenReturn(r.reason);
                        when(set.getString("note")).thenReturn(r.note);
                        when(set.getString("ref_type")).thenReturn(r.refType);
                        when(set.getString("ref_id")).thenReturn(r.refId);
                        when(set.getTimestamp("created_at"))
                                .thenReturn(Timestamp.from(Instant.parse("2026-10-18T05:00:00Z").minusSeconds(r.id)));
                        out.add(m.mapRow(set, i++));
                    }
                    return out;
                });
    }

    @Test
    void detailCodeMappingAndPassThrough() {
        rows(List.of(
                new R(5, "SPEND_DIGITAL", 100, null, null, "ID_HD", "9"),
                new R(4, "POST_PUBLISHED", 5, null, null, "CONTENT_POST", "77"),
                new R(3, "POST_PUBLISHED", -5, "TAKEDOWN", null, "CONTENT_POST", "78"),
                new R(2, "ADMIN_ADJUST", 30, "ADMIN_ADJUST", "Kompensasi bug", null, null),
                new R(1, "LIKE_GIVEN", 1, null, null, "CONTENT_POST", "77")));
        when(jdbc.queryForList(eq(GrowthLedgerPageQuery.POST_TYPES_SQL), anyMap()))
                .thenReturn(List.of(Map.of("id", 77L, "type", "GROWTH_MOMENT")));

        GrowthLedgerPage page = query.page(7L, null);

        List<GrowthLedgerRow> items = page.items();
        assertThat(items).extracting(GrowthLedgerRow::detailCode)
                .containsExactly("ID_HD", "GROWTH_MOMENT", null, null, null); // 78 已物理删除 → null
        assertThat(items.get(2).reason()).isEqualTo("TAKEDOWN");
        assertThat(items.get(3).note()).isEqualTo("Kompensasi bug");
        assertThat(page.nextCursor()).isNull();
        verify(jdbc, times(1)).queryForList(eq(GrowthLedgerPageQuery.POST_TYPES_SQL), anyMap()); // 一页一次
    }

    @Test
    void fullPageReturnsCursorOfLastRow() {
        List<R> rs = new ArrayList<>();
        for (int i = 21; i >= 1; i--) {
            rs.add(new R(i, "LIKE_GIVEN", 1, null, null, "CONTENT_POST", "1"));
        }
        rows(rs);

        GrowthLedgerPage page = query.page(7L, null);

        assertThat(page.items()).hasSize(20);
        KeysetCursor c = KeysetCursor.decodeOrNull(page.nextCursor());
        assertThat(c).isNotNull();
        assertThat(c.id()).isEqualTo(2L);
        verify(jdbc, never()).queryForList(eq(GrowthLedgerPageQuery.POST_TYPES_SQL), anyMap());
    }

    @Test
    void invalidCursorIs400() {
        assertThatThrownBy(() -> query.page(7L, "%%%not-a-cursor"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(400));
    }
}

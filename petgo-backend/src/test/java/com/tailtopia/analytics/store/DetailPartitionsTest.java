package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** L0：明细层分区命名 / 边界 / 已建日期解析（analytics-pipeline Story 1.6 T2）。 */
class DetailPartitionsTest {

    @Test
    void ensuresTodayPlusSevenDaysAcrossYearEnd() {
        List<LocalDate> days = DetailPartitions.daysToEnsure(LocalDate.of(2026, 12, 28));

        assertThat(days).hasSize(8);
        assertThat(days.get(0)).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(days.get(7)).isEqualTo(LocalDate.of(2027, 1, 4));
    }

    @Test
    void nameAndBounds() {
        LocalDate d = LocalDate.of(2027, 2, 28);

        assertThat(DetailPartitions.partitionName(d)).isEqualTo("events_detail_p20270228");
        assertThat(DetailPartitions.createSql(d)).isEqualTo("CREATE TABLE IF NOT EXISTS events_detail_p20270228 "
                + "PARTITION OF events_detail FOR VALUES FROM ('2027-02-28') TO ('2027-03-01')");
    }

    @Test
    void dateOfRoundTripsAndRejectsForeignNames() {
        assertThat(DetailPartitions.dateOf("events_detail_p20261231")).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(DetailPartitions.dateOf("events_detail_p20261332")).isNull();
        assertThat(DetailPartitions.dateOf("events_detail_pdefault")).isNull();
        assertThat(DetailPartitions.dateOf("events_raw_p20261231")).isNull();
        assertThat(DetailPartitions.dateOf(null)).isNull();
    }

    @Test
    void rejectsUnsafeNames() {
        assertThatThrownBy(() -> DetailPartitions.requireSafeName("events_detail_p2026; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builtDatesParsesAttachedPartitions() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("events_detail_p20261120", "events_detail_p20261121", "something_else"));

        assertThat(DetailPartitions.builtDates(jdbc))
                .containsExactlyInAnyOrder(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21));
    }
}

package com.tailtopia.admin.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.dashboard.config.DashboardProperties;
import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import com.tailtopia.admin.dashboard.domain.OpsDailyMetric;
import com.tailtopia.admin.dashboard.metrics.MetricQuery;
import com.tailtopia.admin.dashboard.repository.OpsDailyMetricRepository;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * L0：自愈跑批的缺日算法与单日物化（V1.3.0 Story 3.3 AC1 / AC4）。
 * 事务管理器与仓储全 mock：验证「缺日只补缺的」「单日 27 行」「某项失败该日不落任何行、其余日照常」「空集存 0」「已存在的日期不重算」。
 */
class DashboardMaterializerTest {

    private final OpsDailyMetricRepository repo = mock(OpsDailyMetricRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);

    private static MetricQuery query(DashboardMetric m, BigDecimal value) {
        MetricQuery q = mock(MetricQuery.class);
        when(q.key()).thenReturn(m);
        when(q.compute(any(), any())).thenReturn(value);
        return q;
    }

    private static List<MetricQuery> allQueries() {
        List<MetricQuery> out = new ArrayList<>();
        for (DashboardMetric m : DashboardMetric.values()) {
            out.add(query(m, BigDecimal.valueOf(m.number())));
        }
        return out;
    }

    private DashboardMaterializer materializer(List<MetricQuery> queries, LocalDate from) {
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new DashboardMaterializer(queries, repo, jdbc, tm, new DashboardProperties("0 0 1 * * *", from));
    }

    @Test
    void missingDatesIsAscendingSetDifferenceAndEmptyWhenFromAfterEnd() {
        LocalDate from = LocalDate.of(2026, 7, 17);
        assertThat(DashboardMaterializer.missingDates(from, from.minusDays(1), List.of())).isEmpty();
        assertThat(DashboardMaterializer.missingDates(from, from, List.of())).containsExactly(from);
        // 5 天里中间缺 2 天 → 只补那 2 天，升序
        List<LocalDate> existing = List.of(from, from.plusDays(1), from.plusDays(4));
        assertThat(DashboardMaterializer.missingDates(from, from.plusDays(4), existing))
                .containsExactly(from.plusDays(2), from.plusDays(3));
    }

    @Test
    void rejectsIncompleteOrDuplicateQuerySets() {
        List<MetricQuery> missingOne = allQueries().subList(0, 17);
        assertThatThrownBy(() -> materializer(missingOne, LocalDate.of(2026, 7, 17)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing");
        List<MetricQuery> dup = allQueries();
        dup.add(query(DashboardMetric.NEW_USERS, BigDecimal.ONE));
        assertThatThrownBy(() -> materializer(dup, LocalDate.of(2026, 7, 17)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
    }

    @Test
    @SuppressWarnings("unchecked")
    void materializesOnlyMissingDaysWith27RowsEach() {
        LocalDate yesterday = LocalDate.now(ScheduleWindow.WIB).minusDays(1);
        LocalDate from = yesterday.minusDays(2);
        when(repo.findDistinctReportDates()).thenReturn(List.of(from.plusDays(1)));
        when(repo.existsByReportDate(any())).thenReturn(false);
        DashboardMaterializer m = materializer(allQueries(), from);

        DashboardMaterializer.Result r = m.materializeMissing();

        assertThat(r.materializedDates()).containsExactly(from, yesterday);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.failedDates()).isEmpty();
        ArgumentCaptor<List<OpsDailyMetric>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo, times(2)).saveAll(captor.capture());
        verify(jdbc, times(2)).execute(DashboardMaterializer.LOCK_SQL);
        for (List<OpsDailyMetric> rows : captor.getAllValues()) {
            assertThat(rows).hasSize(27);
            Set<String> realKeys = rows.stream().filter(x -> x.getScope() == MetricScope.REAL)
                    .map(OpsDailyMetric::getMetricKey).collect(Collectors.toSet());
            assertThat(realKeys).hasSize(9).allMatch(k -> DashboardMetric.fromKey(k).dualScope());
            assertThat(rows).allSatisfy(x -> assertThat(x.getComputedAt()).isNotNull());
            OpsDailyMetric newUsers = rows.stream().filter(x -> x.getMetricKey().equals("new_users")).findFirst().orElseThrow();
            assertThat(newUsers.getValue()).isEqualByComparingTo("1");
        }
    }

    @Test
    void failingMetricRollsBackThatDayOnlyAndNullAverageIsStoredAsZero() {
        LocalDate yesterday = LocalDate.now(ScheduleWindow.WIB).minusDays(1);
        LocalDate from = yesterday.minusDays(1);
        when(repo.findDistinctReportDates()).thenReturn(List.of());
        when(repo.existsByReportDate(any())).thenReturn(false);
        List<MetricQuery> qs = allQueries();
        // #12 空集 → null；#8 在 from 那天抛异常
        MetricQuery avg = qs.stream().filter(q -> q.key() == DashboardMetric.ALL_POSTS_AVG_SCORE).findFirst().orElseThrow();
        when(avg.compute(any(), any())).thenReturn(null);
        MetricQuery interacted = qs.stream().filter(q -> q.key() == DashboardMetric.INTERACTED_POSTS).findFirst().orElseThrow();
        when(interacted.compute(eq(from), any())).thenThrow(new IllegalStateException("db down"));
        when(interacted.compute(eq(yesterday), any())).thenReturn(BigDecimal.TEN);
        DashboardMaterializer m = materializer(qs, from);

        DashboardMaterializer.Result r = m.materializeMissing();

        assertThat(r.failedDates()).containsExactly(from);
        assertThat(r.materializedDates()).containsExactly(yesterday);
        verify(tm).rollback(any());
        verify(tm).commit(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpsDailyMetric>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo, times(1)).saveAll(captor.capture());
        assertThat(captor.getValue()).allSatisfy(x -> assertThat(x.getReportDate()).isEqualTo(yesterday));
        assertThat(captor.getValue().stream().filter(x -> x.getMetricKey().equals("all_posts_avg_score")))
                .hasSize(2).allSatisfy(x -> assertThat(x.getValue()).isEqualByComparingTo("0"));
    }

    @Test
    void concurrentRunnerAlreadyMaterializedDayIsSkippedAfterLock() {
        LocalDate yesterday = LocalDate.now(ScheduleWindow.WIB).minusDays(1);
        when(repo.findDistinctReportDates()).thenReturn(List.of());
        when(repo.existsByReportDate(yesterday)).thenReturn(true); // 拿到锁后复查：别的跑批已写
        DashboardMaterializer m = materializer(allQueries(), yesterday);

        DashboardMaterializer.Result r = m.materializeMissing();

        assertThat(r.materializedDates()).isEmpty();
        assertThat(r.skipped()).isEqualTo(1);
        verify(repo, never()).saveAll(anyList());
    }

    @Test
    void scheduledEntrySwallowsFailures() {
        when(repo.findDistinctReportDates()).thenThrow(new IllegalStateException("boom"));
        DashboardMaterializer m = materializer(allQueries(), LocalDate.of(2026, 7, 17));
        m.run(); // 不抛
    }
}

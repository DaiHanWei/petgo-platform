package com.tailtopia.analytics.erasure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsLockKeys;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.erasure.ErasureJob.Outcome;
import com.tailtopia.analytics.erasure.ErasureJob.RunResult;
import com.tailtopia.analytics.erasure.ErasureJob.Target;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** L0：注销擦除任务的纯计算与跳过 / 锁 / 失败分支（analytics-pipeline Story 1.8 AC2 / AC3）。 */
class ErasureJobTest {

    /** UTC 2026-12-31 19:30 = WIB 2027-01-01 02:30：告警日期按 WIB。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-31T19:30:00Z"), ZoneOffset.UTC);
    private static final String KEY_7 = "k7";

    private final AnalyticsPipelineState state = new AnalyticsPipelineState();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcTemplate business = mock(JdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final EventCatalog catalog = mock(EventCatalog.class);
    private final OpsAlertService alerts = mock(OpsAlertService.class);
    private final ErasureJob job = new ErasureJob(state, jdbc, tx, business, catalog, alerts, "test", "30min", CLOCK);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(catalog.isLoaded()).thenReturn(true);
        when(catalog.userIdentifierKeys()).thenReturn(Set.of("owner_id"));
        when(tx.execute(any(TransactionCallback.class)))
                .thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
    }

    @SuppressWarnings("unchecked")
    private void outbox(List<Target> pending, List<Target> recentDone) {
        // loadTargets 走 query(String, RowMapper, Object...)：Mockito 5 用 any(Object[].class) 匹配任意个数的变参
        when(business.query(eq(ErasureJob.PENDING_SQL), any(RowMapper.class), any(Object[].class)))
                .thenReturn((List) pending);
        when(business.query(eq(ErasureJob.RECENT_DONE_SQL), any(RowMapper.class), any(Object[].class)))
                .thenReturn((List) recentDone);
    }

    // ---------------------------------------------------------------- 纯计算

    @Test
    void identifierValuesIncludeDecimalIdUserKeyAndPublicToken() {
        Set<String> v = ErasureJob.identifierValues(List.of(new Target(7L, KEY_7, "tok7", null),
                new Target(8L, "k8", null, null)));

        // 1-2 复审结转：owner_id 的值是用户公开 token，按值匹配必须带上它
        assertThat(v).containsExactly("7", KEY_7, "tok7", "8", "k8");
    }

    @Test
    void latePassOnlyScansPartitionsOnOrAfterDoneDayPlusDefault() {
        Target a = new Target(7L, KEY_7, null, Instant.parse("2026-12-28T23:00:00Z"));
        Target b = new Target(8L, "k8", null, Instant.parse("2026-12-30T01:00:00Z"));
        Set<LocalDate> raw = Set.of(LocalDate.of(2026, 12, 27), LocalDate.of(2026, 12, 28),
                LocalDate.of(2026, 12, 29), LocalDate.of(2026, 12, 30));

        Map<String, List<Target>> m = ErasureJob.latePartitions(raw, true, List.of(a, b));

        assertThat(m.keySet()).containsExactly("events_raw_p20261228", "events_raw_p20261229", "events_raw_p20261230",
                "events_raw_pdefault");
        assertThat(m.get("events_raw_p20261228")).containsExactly(a);
        assertThat(m.get("events_raw_p20261229")).containsExactly(a);
        assertThat(m.get("events_raw_p20261230")).containsExactly(a, b);
        assertThat(m.get("events_raw_pdefault")).containsExactly(a, b);
        assertThat(ErasureJob.latePartitions(raw, false, List.of(b)).keySet()).containsExactly("events_raw_p20261230");
    }

    @Test
    void rawPartitionsAreRegeneratedFromSafeNamesWithDefaultLast() {
        assertThat(ErasureJob.rawPartitions(List.of("events_raw_pdefault", "events_raw_p20261202",
                "events_raw_p20261201", "evil; DROP TABLE x")))
                .containsExactly("events_raw_p20261201", "events_raw_p20261202", "events_raw_pdefault");
    }

    @Test
    void erasureSqlNullsIdentityAndStripsKeysWithoutDeletingRows() {
        String own = ErasureJob.ownRowsSql("events_raw_p20261201");
        assertThat(own).startsWith("UPDATE events_raw_p20261201 SET user_id = NULL, user_key = NULL, "
                + "properties = properties - ?::text[]").doesNotContain("DELETE");
        assertThat(ErasureJob.valueStripSql("events_detail_p20261201"))
                .isEqualTo("UPDATE events_detail_p20261201 SET properties = properties - ?::text "
                        + "WHERE properties ->> ?::text = ANY(?)");
    }

    @Test
    void statementTimeoutMustBeSafeToConcatenate() {
        assertThat(ErasureJob.requireValidTimeout(" 30min ")).isEqualTo("30min");
        assertThat(ErasureJob.requireValidTimeout("1800s")).isEqualTo("1800s");
        assertThatThrownBy(() -> ErasureJob.requireValidTimeout("30min'; DROP TABLE x; --"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ErasureJob.requireValidTimeout(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 跳过 / 锁 / 失败

    @Test
    void skipsAndAlertsWhenPipelineDisabled() {
        state.disable(Reason.DB_UNAVAILABLE, "x");

        assertThat(job.runOnce().outcome()).isEqualTo(Outcome.SKIPPED_DISABLED);

        // 擦除承诺 24 小时：不能静默跳过
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics:erasure-skipped:2027-01-01"),
                contains("DB_UNAVAILABLE"));
        verifyNoInteractions(jdbc, business, tx);
    }

    @Test
    void nothingPendingTouchesNoAnalyticsTable() {
        outbox(List.of(), List.of());

        RunResult r = job.runOnce();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.pending()).isZero();
        verifyNoInteractions(jdbc, tx, alerts);
    }

    @Test
    void lockNotAcquiredGivesUpWithoutMarkingDone() {
        outbox(List.of(new Target(7L, KEY_7, null, null)), List.of());
        when(jdbc.queryForObject(ErasureJob.LOCK_SQL, Boolean.class, AnalyticsLockKeys.NAMESPACE,
                AnalyticsLockKeys.ERASURE)).thenReturn(false);

        assertThat(job.runOnce().outcome()).isEqualTo(Outcome.SKIPPED_LOCKED);

        verify(business, never()).update(eq(ErasureJob.MARK_DONE_SQL), any(PreparedStatementSetter.class));
        verify(jdbc, never()).update(eq(ErasureJob.DELETE_LINKS_SQL), any(PreparedStatementSetter.class));
        verifyNoInteractions(alerts);
    }

    @Test
    void successRunsEveryPartitionUnderLockAndMarksDone() {
        outbox(List.of(new Target(7L, KEY_7, null, null)), List.of());
        when(jdbc.queryForObject(ErasureJob.LOCK_SQL, Boolean.class, AnalyticsLockKeys.NAMESPACE,
                AnalyticsLockKeys.ERASURE)).thenReturn(true);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return sql.contains("'events_detail'") ? List.of("events_detail_p20261231")
                    : List.of("events_raw_p20261231", "events_raw_pdefault");
        });
        when(jdbc.queryForObject(eq(ErasureJob.EXISTS_SQL), eq(Boolean.class), anyString())).thenReturn(true);
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

        RunResult r = job.runOnce();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.pending()).isEqualTo(1);
        assertThat(r.linksDeleted()).isEqualTo(1);
        // 每分区：本人行 1 条 + 每个标识键 1 条（清单只有 owner_id）
        assertThat(r.detailRows()).isEqualTo(2);
        assertThat(r.rawRows()).isEqualTo(4);
        verify(jdbc).update(eq(ErasureJob.ownRowsSql("events_detail_p20261231")), any(PreparedStatementSetter.class));
        verify(jdbc).update(eq(ErasureJob.ownRowsSql("events_raw_p20261231")), any(PreparedStatementSetter.class));
        verify(jdbc).update(eq(ErasureJob.ownRowsSql("events_raw_pdefault")), any(PreparedStatementSetter.class));
        verify(jdbc, org.mockito.Mockito.times(4)).execute("SET LOCAL statement_timeout = '30min'");
        verify(business).update(eq(ErasureJob.MARK_DONE_SQL), any(PreparedStatementSetter.class));
        verifyNoInteractions(alerts);
    }

    @Test
    void latePassAlsoRedeletesIdentityLinksWithoutMarkingDone() {
        // 复审 2026-11-12：注销后 token 有效窗口内补发的 user_identified 会重建关联 → 迟到兜底须重删关联表
        outbox(List.of(), List.of(new Target(7L, KEY_7, null, Instant.parse("2026-12-30T19:05:00Z"))));
        when(jdbc.queryForObject(ErasureJob.LOCK_SQL, Boolean.class, AnalyticsLockKeys.NAMESPACE,
                AnalyticsLockKeys.ERASURE)).thenReturn(true);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("events_raw_p20261231"));
        when(jdbc.queryForObject(eq(ErasureJob.EXISTS_SQL), eq(Boolean.class), anyString())).thenReturn(true);
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

        RunResult r = job.runOnce();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.pending()).isZero();
        assertThat(r.late()).isEqualTo(1);
        assertThat(r.linksDeleted()).isEqualTo(1);
        verify(jdbc).update(eq(ErasureJob.DELETE_LINKS_SQL), any(PreparedStatementSetter.class));
        verify(jdbc).update(eq(ErasureJob.ownRowsSql("events_raw_p20261231")), any(PreparedStatementSetter.class));
        verify(business, never()).update(eq(ErasureJob.MARK_DONE_SQL), any(PreparedStatementSetter.class));
    }

    @Test
    void failureAlertsKeyedByWibDayWithoutDetailsAndDoesNotMarkDone() {
        outbox(List.of(new Target(7L, KEY_7, null, null)), List.of());
        when(jdbc.queryForObject(ErasureJob.LOCK_SQL, Boolean.class, AnalyticsLockKeys.NAMESPACE,
                AnalyticsLockKeys.ERASURE)).thenReturn(true);
        when(jdbc.update(eq(ErasureJob.DELETE_LINKS_SQL), any(PreparedStatementSetter.class)))
                .thenThrow(new DataAccessResourceFailureException("jdbc:postgresql://secret-host"));

        assertThat(job.runOnce().outcome()).isEqualTo(Outcome.FAILED);

        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics:erasure-failed:2027-01-01"),
                contains("DataAccessResourceFailureException"));
        verify(alerts, never()).send(any(), anyString(), contains("secret-host"));
        verify(business, never()).update(eq(ErasureJob.MARK_DONE_SQL), any(PreparedStatementSetter.class));
    }
}

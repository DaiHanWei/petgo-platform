package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsLockKeys;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.rollup.RollupProgressRepository.Progress;
import com.tailtopia.analytics.rollup.RollupService.Outcome;
import com.tailtopia.analytics.rollup.RollupService.RunResult;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** L0：续算起点、保留期截断、重跑参数校验、依赖排序与失败处理（Story 4.1 T5 / T8），全部 mock，无库。 */
class RollupServiceTest {

    private static final LocalDate AS_OF = LocalDate.parse("2026-11-20");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T20:00:00Z"), ZoneOffset.UTC);

    private AnalyticsPipelineState state;
    private JdbcTemplate jdbc;
    private JdbcTemplate businessJdbc;
    private PlatformTransactionManager txm;
    private RollupProgressRepository progress;
    private OpsAlertService alerts;

    /** 记录被调用的日期，指定日期抛异常。 */
    private static final class FakeFamily implements RollupFamily {

        final String name;
        final Set<String> deps;
        final LocalDate failOn;
        final List<LocalDate> days = new ArrayList<>();

        FakeFamily(String name, Set<String> deps, LocalDate failOn) {
            this.name = name;
            this.deps = deps;
            this.failOn = failOn;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> dependsOn() {
            return deps;
        }

        @Override
        public void computeDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
            days.add(statDate);
            if (statDate.equals(failOn)) {
                throw new DataAccessResourceFailureException("boom", new SQLException("x"));
            }
        }
    }

    @BeforeEach
    void setUp() {
        state = new AnalyticsPipelineState();
        jdbc = mock(JdbcTemplate.class);
        businessJdbc = mock(JdbcTemplate.class);
        txm = mock(PlatformTransactionManager.class);
        progress = mock(RollupProgressRepository.class);
        alerts = mock(OpsAlertService.class);
        when(jdbc.queryForObject(RollupService.LOCK_SQL, Boolean.class, AnalyticsLockKeys.NAMESPACE,
                AnalyticsLockKeys.ROLLUP)).thenReturn(true);
        when(businessJdbc.queryForList(RollupContext.EXCLUDED_USERS_SQL, Long.class)).thenReturn(List.of(7L, 9L));
        when(progress.findAll()).thenReturn(Map.of());
    }

    private RollupService service(RollupFamily... families) {
        return new RollupService(state, jdbc, new TransactionTemplate(txm), businessJdbc, progress, List.of(families),
                AnalyticsRollupProperties.defaults(), alerts, "test", CLOCK);
    }

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    // ---------------------------------------------------------------- 续算起点

    @Test
    void startDateFirstRunOnlyRecomputeWindow() {
        assertThat(RollupService.startDate(null, AS_OF, 3)).isEqualTo(d("2026-11-18"));
    }

    @Test
    void startDateNormalRunRecomputesLastThreeDays() {
        assertThat(RollupService.startDate(d("2026-11-19"), AS_OF, 3)).isEqualTo(d("2026-11-18"));
        // 已经算到 asOf（同一天跑第二次）也仍重算 3 天
        assertThat(RollupService.startDate(AS_OF, AS_OF, 3)).isEqualTo(d("2026-11-18"));
    }

    @Test
    void startDateCatchesUpMissingDays() {
        assertThat(RollupService.startDate(d("2026-11-10"), AS_OF, 3)).isEqualTo(d("2026-11-11"));
        assertThat(RollupService.startDate(d("2026-11-10"), AS_OF, 16)).isEqualTo(d("2026-11-05"));
    }

    @Test
    void floorIsAsOfMinusRetentionButNeverBeforeKeptDetailPartitions() {
        // 明细层删 < 今天(11-21) − 3 个月 = 08-21 的分区：asOf − 92 = 08-20 已被删，下限取 08-21
        assertThat(RollupService.floor(AS_OF, 92)).isEqualTo(d("2026-08-21"));
        // 3 个月只有 90 天（今天 03-01 → 12-01）：asOf − 92 = 11-29，下限取 12-01
        assertThat(RollupService.floor(d("2027-02-28"), 92)).isEqualTo(d("2026-12-01"));
        // 配置更严时以配置为准
        assertThat(RollupService.floor(AS_OF, 30)).isEqualTo(d("2026-10-21"));
    }

    @Test
    void lagBeyondRetentionIsClampedAndAlerted() {
        FakeFamily f = new FakeFamily("activity", Set.of(), null);
        when(progress.findAll()).thenReturn(Map.of("activity",
                new Progress("activity", d("2026-01-01"), null, null)));
        RunResult r = service(f).runScheduled(AS_OF);

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(f.days.get(0)).isEqualTo(d("2026-08-21"));
        assertThat(f.days.get(f.days.size() - 1)).isEqualTo(AS_OF);
        assertThat(r.units()).isEqualTo(92);
        // 截断后从下限起推进进度（被跳过的天明确放弃）
        verify(progress).markDone("activity", d("2026-08-21"), CLOCK.instant());
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-rollup-lag:activity:2026-11-20"), anyString());
    }

    // ---------------------------------------------------------------- 重跑校验

    @Test
    void validateRerun() {
        Set<String> known = Set.of("activity", "install");
        assertThat(RollupService.validateRerun(d("2026-11-01"), d("2026-11-07"), AS_OF, 92, null, known)).isEmpty();
        assertThat(RollupService.validateRerun(d("2026-08-21"), AS_OF, AS_OF, 92, List.of("install"), known))
                .isEmpty();
        // asOf − 92 天，但该日明细分区已被删（今天 − 3 个月 = 08-21）
        assertThat(RollupService.validateRerun(d("2026-08-20"), AS_OF, AS_OF, 92, null, known))
                .contains(RollupService.REJECT_OUT_OF_RANGE);
        // from > to
        assertThat(RollupService.validateRerun(d("2026-11-08"), d("2026-11-07"), AS_OF, 92, null, known))
                .contains(RollupService.REJECT_OUT_OF_RANGE);
        // to > asOf（今天还没过完）
        assertThat(RollupService.validateRerun(d("2026-11-19"), d("2026-11-21"), AS_OF, 92, null, known))
                .contains(RollupService.REJECT_OUT_OF_RANGE);
        // from 早于保留期下限
        assertThat(RollupService.validateRerun(d("2026-08-19"), d("2026-11-07"), AS_OF, 92, null, known))
                .contains(RollupService.REJECT_OUT_OF_RANGE);
        assertThat(RollupService.validateRerun(null, d("2026-11-07"), AS_OF, 92, null, known))
                .contains(RollupService.REJECT_OUT_OF_RANGE);
        assertThat(RollupService.validateRerun(d("2026-11-01"), d("2026-11-07"), AS_OF, 92, List.of("dau"), known))
                .contains(RollupService.REJECT_UNKNOWN_FAMILY);
    }

    // ---------------------------------------------------------------- 依赖排序

    @Test
    void ordersByDependencies() {
        FakeFamily a = new FakeFamily("reconcile", Set.of("activity"), null);
        FakeFamily b = new FakeFamily("activity", Set.of(), null);
        FakeFamily c = new FakeFamily("install", Set.of(), null);
        assertThat(RollupService.orderByDependencies(List.of(a, b, c))).extracting(RollupFamily::name)
                .containsExactly("activity", "reconcile", "install");
    }

    @Test
    void rejectsUnknownDependencyCycleAndDuplicate() {
        assertThatThrownBy(() -> RollupService.orderByDependencies(
                List.of(new FakeFamily("a", Set.of("zzz"), null)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RollupService.orderByDependencies(List.of(new FakeFamily("a", Set.of("b"), null),
                new FakeFamily("b", Set.of("a"), null)))).hasMessageContaining("循环");
        assertThatThrownBy(() -> RollupService.orderByDependencies(List.of(new FakeFamily("a", Set.of(), null),
                new FakeFamily("a", Set.of(), null)))).hasMessageContaining("重名");
    }

    // ---------------------------------------------------------------- 执行

    @Test
    void scheduledRunsEachDayInOwnUnitAndAdvancesProgress() {
        FakeFamily f = new FakeFamily("activity", Set.of(), null);
        RunResult r = service(f).runScheduled(AS_OF);

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(f.days).containsExactly(d("2026-11-18"), d("2026-11-19"), AS_OF);
        InOrder o = inOrder(jdbc, progress);
        for (LocalDate day : f.days) {
            o.verify(jdbc).queryForObject(RollupService.LOCK_SQL, Boolean.class, AnalyticsLockKeys.NAMESPACE,
                    AnalyticsLockKeys.ROLLUP);
            o.verify(jdbc).execute("SET LOCAL statement_timeout = '600s'");
            o.verify(jdbc).execute(RollupContext.CREATE_TMP_SQL);
            o.verify(progress).markDone("activity", day, CLOCK.instant());
        }
        verify(alerts, never()).send(any(), anyString(), anyString());
    }

    @Test
    void failureStopsFamilyDoesNotAdvanceAndAlertsOutsideTx() {
        FakeFamily bad = new FakeFamily("activity", Set.of(), d("2026-11-19"));
        FakeFamily dependent = new FakeFamily("reconcile", Set.of("activity"), null);
        FakeFamily other = new FakeFamily("install", Set.of(), null);
        RunResult r = service(bad, dependent, other).runScheduled(AS_OF);

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.failedFamilies()).containsExactly("activity");
        assertThat(r.skippedFamilies()).containsExactly("reconcile");
        // 失败日之后不再算；进度只推进到失败前一天
        assertThat(bad.days).containsExactly(d("2026-11-18"), d("2026-11-19"));
        verify(progress).markDone("activity", d("2026-11-18"), CLOCK.instant());
        verify(progress, never()).markDone("activity", d("2026-11-19"), CLOCK.instant());
        // 依赖失败指标族的跳过；无关指标族照常
        assertThat(dependent.days).isEmpty();
        assertThat(other.days).hasSize(3);
        // 先回滚，再记 last_error、发告警
        InOrder o = inOrder(txm, progress, alerts);
        o.verify(txm).rollback(any());
        o.verify(progress).recordError(eq("activity"), startsWith("DataAccessResourceFailureException"),
                eq(CLOCK.instant()));
        o.verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-rollup:activity:2026-11-19"), anyString());
    }

    @Test
    void lockHeldElsewhereStopsRoundWithoutAlert() {
        when(jdbc.queryForObject(RollupService.LOCK_SQL, Boolean.class, AnalyticsLockKeys.NAMESPACE,
                AnalyticsLockKeys.ROLLUP)).thenReturn(false);
        FakeFamily f = new FakeFamily("activity", Set.of(), null);
        RunResult r = service(f).runScheduled(AS_OF);

        assertThat(r.outcome()).isEqualTo(Outcome.SKIPPED_LOCKED);
        assertThat(f.days).isEmpty();
        verify(progress, never()).recordError(anyString(), anyString(), any());
        verify(alerts, never()).send(any(), anyString(), anyString());
    }

    @Test
    void pipelineDisabledSkips() {
        state.disable(AnalyticsPipelineState.Reason.DB_UNAVAILABLE, "x");
        FakeFamily f = new FakeFamily("activity", Set.of(), null);
        assertThat(service(f).runScheduled(AS_OF).outcome()).isEqualTo(Outcome.SKIPPED_DISABLED);
        assertThat(f.days).isEmpty();
    }

    @Test
    void businessReadFailureAlertsAndDoesNothing() {
        when(businessJdbc.queryForList(RollupContext.EXCLUDED_USERS_SQL, Long.class))
                .thenThrow(new DataAccessResourceFailureException("down"));
        FakeFamily f = new FakeFamily("activity", Set.of(), null);
        assertThat(service(f).runScheduled(AS_OF).outcome()).isEqualTo(Outcome.FAILED);
        assertThat(f.days).isEmpty();
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-rollup-prepare:2026-11-20"), anyString());
    }

    @Test
    void rerunOnlySelectedFamiliesOverRange() {
        FakeFamily a = new FakeFamily("activity", Set.of(), null);
        FakeFamily b = new FakeFamily("install", Set.of(), null);
        RunResult r = service(a, b).rerun(d("2026-11-01"), d("2026-11-02"), List.of("install"), AS_OF);

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(a.days).isEmpty();
        assertThat(b.days).containsExactly(d("2026-11-01"), d("2026-11-02"));
        assertThat(r.units()).isEqualTo(2);
    }

    @Test
    void rerunDoesNotJumpProgressOverGap() {
        when(progress.findAll()).thenReturn(Map.of("activity",
                new Progress("activity", d("2026-11-10"), null, null)));
        FakeFamily f = new FakeFamily("activity", Set.of(), null);
        RollupService svc = service(f);

        // 11-11 ~ 11-14 没算过：重跑 11-15 ~ 11-16 只写数据，不推进进度（否则缺口永远补不上）
        assertThat(svc.rerun(d("2026-11-15"), d("2026-11-16"), null, AS_OF).outcome()).isEqualTo(Outcome.DONE);
        assertThat(f.days).containsExactly(d("2026-11-15"), d("2026-11-16"));
        verify(progress, never()).markDone(eq("activity"), any(), any());

        // 与进度相连的重跑照常逐日推进
        assertThat(svc.rerun(d("2026-11-09"), d("2026-11-12"), null, AS_OF).outcome()).isEqualTo(Outcome.DONE);
        verify(progress).markDone("activity", d("2026-11-09"), CLOCK.instant());
        verify(progress).markDone("activity", d("2026-11-11"), CLOCK.instant());
        verify(progress).markDone("activity", d("2026-11-12"), CLOCK.instant());
    }

    @Test
    void describeHasOnlyClassNames() {
        String s = RollupService.describe(new DataAccessResourceFailureException("secret", new SQLException("pw")));
        assertThat(s).isEqualTo("DataAccessResourceFailureException <- SQLException").doesNotContain("secret");
        assertThat(Optional.of(s)).isPresent();
    }
}

package com.tailtopia.admin.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.dashboard.config.DashboardProperties;
import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import com.tailtopia.admin.dashboard.domain.OpsDailyMetric;
import com.tailtopia.admin.dashboard.metrics.MetricQuery;
import com.tailtopia.admin.dashboard.repository.OpsDailyMetricRepository;
import com.tailtopia.admin.dashboard.web.AdminDashboardMaterializeController;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * L1（真库）：自愈跑批端到端（V1.3.0 Story 3.3 AC1 / AC2 / AC3 默认 profile 侧）。
 * 用独立的 {@link DashboardMaterializer} 实例 + 很短的回填窗口（近 3 天）跑，不改共享库里其它日期；测试后删掉本测试写的行。
 * 「某日失败 → 该日无任何行、其余日正常」用一个会在指定日期抛异常的包装查询模拟。
 */
class DashboardMaterializerIntegrationTest extends com.tailtopia.support.ApiIntegrationTest {

    @Autowired
    private List<MetricQuery> queries;

    @Autowired
    private OpsDailyMetricRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager tm;

    @Autowired
    private ApplicationContext context;

    private final LocalDate yesterday = LocalDate.now(ScheduleWindow.WIB).minusDays(1);
    private final LocalDate from = yesterday.minusDays(2);

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ops_daily_metrics WHERE report_date BETWEEN ? AND ?", java.sql.Date.valueOf(from),
                java.sql.Date.valueOf(yesterday));
    }

    private DashboardMaterializer materializer(List<MetricQuery> qs) {
        return new DashboardMaterializer(qs, repo, jdbc, tm, new DashboardProperties("0 0 1 * * *", from));
    }

    private long rows(LocalDate d) {
        return repo.findByReportDateBetweenOrderByReportDate(d, d).size();
    }

    @Test
    void backfillsMissingDaysOnlyAndNeverRewritesExistingRows() {
        cleanup();
        DashboardMaterializer m = materializer(queries);

        DashboardMaterializer.Result first = m.materializeMissing();
        assertThat(first.materializedDates()).containsExactly(from, from.plusDays(1), yesterday);
        assertThat(first.failedDates()).isEmpty();
        for (LocalDate d = from; !d.isAfter(yesterday); d = d.plusDays(1)) {
            assertThat(rows(d)).as(d.toString()).isEqualTo(27);
        }
        Map<String, BigDecimal> before = snapshot(yesterday);

        // 人为删掉中间一天 → 再跑只补该日；其它日 value 不变（行不可变）
        jdbc.update("DELETE FROM ops_daily_metrics WHERE report_date = ?", java.sql.Date.valueOf(from.plusDays(1)));
        DashboardMaterializer.Result second = m.materializeMissing();
        assertThat(second.materializedDates()).containsExactly(from.plusDays(1));
        assertThat(second.skipped()).isEqualTo(2);
        assertThat(rows(from.plusDays(1))).isEqualTo(27);
        assertThat(snapshot(yesterday)).isEqualTo(before);

        // 第三次：无缺日，什么都不写
        assertThat(m.materializeMissing().materializedDates()).isEmpty();
    }

    @Test
    void failingMetricLeavesNoRowsForThatDayAndOtherDaysSucceed() {
        cleanup();
        List<MetricQuery> wrapped = new ArrayList<>();
        for (MetricQuery q : queries) {
            wrapped.add(q.key() == DashboardMetric.SILENT_POSTS ? new FailOn(q, from.plusDays(1)) : q);
        }
        DashboardMaterializer m = materializer(wrapped);

        DashboardMaterializer.Result r = m.materializeMissing();

        assertThat(r.failedDates()).containsExactly(from.plusDays(1));
        assertThat(r.materializedDates()).containsExactly(from, yesterday);
        assertThat(rows(from.plusDays(1))).isZero();
        assertThat(rows(from)).isEqualTo(27);
        assertThat(rows(yesterday)).isEqualTo(27);

        // 下一次（查询恢复）自动补齐失败日（OQ-B2）
        assertThat(materializer(queries).materializeMissing().materializedDates()).containsExactly(from.plusDays(1));
    }

    /** 落库中途失败（值超出 NUMERIC(18,4)）→ 该日已写的行随事务一起回滚，最终 0 行；其余日正常。 */
    @Test
    void insertFailureMidwayRollsBackWholeDay() {
        cleanup();
        List<MetricQuery> wrapped = new ArrayList<>();
        for (MetricQuery q : queries) {
            wrapped.add(q.key() == DashboardMetric.PAYMENTS_INCL_PAWCOIN
                    ? new FixedOn(q, from.plusDays(1), new BigDecimal("1e20")) : q);
        }
        DashboardMaterializer m = materializer(wrapped);

        DashboardMaterializer.Result r = m.materializeMissing();

        assertThat(r.failedDates()).containsExactly(from.plusDays(1));
        assertThat(rows(from.plusDays(1))).isZero();
        assertThat(rows(from)).isEqualTo(27);
        assertThat(rows(yesterday)).isEqualTo(27);
    }

    @Test
    void manualEndpointIsNotRegisteredOutsideStagProfile() {
        assertThat(context.getBeansOfType(AdminDashboardMaterializeController.class)).isEmpty();
    }

    private Map<String, BigDecimal> snapshot(LocalDate d) {
        Map<String, BigDecimal> out = new java.util.TreeMap<>();
        for (OpsDailyMetric x : repo.findByReportDateBetweenOrderByReportDate(d, d)) {
            out.put(x.getMetricKey() + "/" + x.getScope(), x.getValue());
        }
        return out;
    }

    /** 在指定日期返回固定值的包装查询（模拟「落库中途失败」：值超出列精度）。 */
    private record FixedOn(MetricQuery delegate, LocalDate day, BigDecimal value) implements MetricQuery {
        @Override
        public DashboardMetric key() {
            return delegate.key();
        }

        @Override
        public BigDecimal compute(LocalDate reportDate, MetricScope scope) {
            return reportDate.equals(day) ? value : delegate.compute(reportDate, scope);
        }
    }

    /** 在指定日期抛异常的包装查询（模拟「某日失败」）。 */
    private record FailOn(MetricQuery delegate, LocalDate failDay) implements MetricQuery {
        @Override
        public DashboardMetric key() {
            return delegate.key();
        }

        @Override
        public BigDecimal compute(LocalDate reportDate, MetricScope scope) {
            if (reportDate.equals(failDay)) {
                throw new IllegalStateException("simulated failure");
            }
            return delegate.compute(reportDate, scope);
        }
    }
}

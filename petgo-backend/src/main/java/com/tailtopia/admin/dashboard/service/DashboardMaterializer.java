package com.tailtopia.admin.dashboard.service;

import com.tailtopia.admin.dashboard.config.DashboardProperties;
import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import com.tailtopia.admin.dashboard.domain.OpsDailyMetric;
import com.tailtopia.admin.dashboard.metrics.MetricQuery;
import com.tailtopia.admin.dashboard.repository.OpsDailyMetricRepository;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 看板日粒度物化器（V1.3.0 Story 3.3，AD-3「自愈跑批」）。
 * <p><b>回填与补偿是同一段代码</b>：找出 {@code [backfillFrom, 昨天(WIB)]} 里 {@code ops_daily_metrics} 缺失的日期，按升序逐日物化。
 * 上线首日这一循环完成 D-15 全量回填；某日跑批失败则次日自动补上。运维动作为零，无独立回填脚本。
 * <ul>
 * <li>昨天 = WIB 的昨天（{@link ScheduleWindow#WIB}），cron 也按 WIB 解释；容器时区是 UTC，不能用 {@code LocalDate.now()}。</li>
 * <li>单日一事务（{@link TransactionTemplate}，不用方法级 {@code @Transactional}）：18 项 × 口径 = 27 行一起落库，任一项失败整日回滚、其余日不受影响；已存在的日期不重算不覆盖（行不可变）。</li>
 * <li>并发防护：每日事务内先 {@code pg_advisory_xact_lock(hashtext('ops_daily_metrics'))} 再复查该日是否已存在，定时与 stag 手动触发同时跑也不会重复插入（UNIQUE 约束兜底）。</li>
 * <li>日志只记日期与耗时，不记指标值；单日失败 warn 含日期与异常类名，不含 SQL 参数（AC4）。不写审计（系统行为）。</li>
 * </ul>
 */
@Component
public class DashboardMaterializer {

    private static final Logger log = LoggerFactory.getLogger(DashboardMaterializer.class);

    /** advisory 锁键：与 SQL 端 {@code hashtext('ops_daily_metrics')} 同源，定时 / 手动触发共用。 */
    static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtext('ops_daily_metrics'))";

    /** 一次运行的结果（stag 手动触发原样返回 JSON）。 */
    public record Result(List<LocalDate> materializedDates, int skipped, List<LocalDate> failedDates, long tookMs) {
    }

    private final Map<DashboardMetric, MetricQuery> queries = new EnumMap<>(DashboardMetric.class);
    private final OpsDailyMetricRepository repository;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DashboardProperties props;

    public DashboardMaterializer(List<MetricQuery> queries, OpsDailyMetricRepository repository, JdbcTemplate jdbc,
            PlatformTransactionManager txManager, DashboardProperties props) {
        for (MetricQuery q : queries) {
            MetricQuery prev = this.queries.put(q.key(), q);
            if (prev != null) {
                throw new IllegalStateException("duplicate MetricQuery for " + q.key().key());
            }
        }
        Set<DashboardMetric> missing = new HashSet<>(Set.of(DashboardMetric.values()));
        missing.removeAll(this.queries.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("MetricQuery missing for " + missing);
        }
        this.repository = repository;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        // 明示「单日一事务、跨日互不影响」：即使将来被外层 @Transactional 调用，某日失败也不会把外层置 rollback-only
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.props = props;
    }

    /** 定时入口：每天 WIB 01:00（{@code petgo.admin.dashboard.cron}），兜底 try/catch 照 {@code SipdhExpiryScanner} 范式。 */
    @Scheduled(cron = "${petgo.admin.dashboard.cron:0 0 1 * * *}", zone = "Asia/Jakarta")
    public void run() {
        try {
            materializeMissing();
        } catch (RuntimeException e) {
            log.warn("看板物化跑批失败 cause={}", e.getClass().getSimpleName());
        }
    }

    /** 找缺日 → 逐日物化（AC1）。可被 stag 手动端点同步调用。 */
    public Result materializeMissing() {
        long started = System.nanoTime();
        LocalDate end = LocalDate.now(ScheduleWindow.WIB).minusDays(1);
        LocalDate from = props.backfillFrom();
        List<LocalDate> existing = repository.findDistinctReportDates();
        List<LocalDate> missing = missingDates(from, end, existing);
        int skipped = (int) rangeSize(from, end) - missing.size();
        List<LocalDate> done = new ArrayList<>();
        List<LocalDate> failed = new ArrayList<>();
        for (LocalDate d : missing) {
            try {
                Boolean written = tx.execute(status -> materializeDayLocked(d));
                if (Boolean.TRUE.equals(written)) {
                    done.add(d);
                } else {
                    skipped++;
                }
            } catch (MetricFailure e) {
                failed.add(d);
                // 只记日期 / 指标 key / 口径 / 异常类名；不记指标值、不记 SQL 参数（AC4）
                log.warn("看板物化单日失败 date={} metric={} scope={} cause={}", d, e.metric.key(), e.scope, e.getCause().getClass().getSimpleName());
            } catch (RuntimeException e) {
                failed.add(d);
                log.warn("看板物化单日失败 date={} cause={}", d, e.getClass().getSimpleName());
            }
        }
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        if (!done.isEmpty() || !failed.isEmpty()) {
            log.info("看板物化完成 materialized={} skipped={} failed={} tookMs={}", done.size(), skipped, failed.size(), tookMs);
        }
        return new Result(List.copyOf(done), skipped, List.copyOf(failed), tookMs);
    }

    /** 缺日集合（纯函数，L0）：{@code [from, end]} 减去已物化日期，升序；{@code from > end} → 空。 */
    static List<LocalDate> missingDates(LocalDate from, LocalDate end, Collection<LocalDate> existing) {
        List<LocalDate> out = new ArrayList<>();
        if (from == null || end == null || from.isAfter(end)) {
            return out;
        }
        Set<LocalDate> have = new HashSet<>(existing);
        for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1)) {
            if (!have.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    private static long rangeSize(LocalDate from, LocalDate end) {
        return from == null || end == null || from.isAfter(end) ? 0 : end.toEpochDay() - from.toEpochDay() + 1;
    }

    /** 事务内：advisory 锁 → 复查 → 物化；返回是否真的写入（被并发跑批抢先物化则 false）。 */
    private boolean materializeDayLocked(LocalDate d) {
        jdbc.execute(LOCK_SQL);
        if (repository.existsByReportDate(d)) {
            return false;
        }
        repository.saveAll(materializeDay(d));
        return true;
    }

    /** 单日 27 行：18 项 ALL + 9 项 REAL；平均类空集（null）存 0 并记「无样本」（只记日期与 key，不记值）。 */
    List<OpsDailyMetric> materializeDay(LocalDate d) {
        Instant now = Instant.now();
        List<OpsDailyMetric> rows = new ArrayList<>(27);
        for (DashboardMetric m : DashboardMetric.values()) {
            MetricQuery q = queries.get(m);
            rows.add(OpsDailyMetric.of(d, m, MetricScope.ALL, compute(q, d, m, MetricScope.ALL), now));
            if (m.dualScope()) {
                rows.add(OpsDailyMetric.of(d, m, MetricScope.REAL, compute(q, d, m, MetricScope.REAL), now));
            }
        }
        return rows;
    }

    /** 单项取数；失败时包成 {@link MetricFailure}（带 key / 口径，供 warn 日志定位是哪条 SQL 挂了）。 */
    private static BigDecimal compute(MetricQuery q, LocalDate d, DashboardMetric m, MetricScope scope) {
        try {
            return valueOrZero(q.compute(d, scope), d, m, scope);
        } catch (RuntimeException e) {
            throw new MetricFailure(m, scope, e);
        }
    }

    /** 某项指标取数失败（消息只含 key / 口径 / 原异常类名，不含值与 SQL 参数）。 */
    static final class MetricFailure extends RuntimeException {
        final DashboardMetric metric;
        final MetricScope scope;

        MetricFailure(DashboardMetric metric, MetricScope scope, RuntimeException cause) {
            super("metric " + metric.key() + "/" + scope + " failed: " + cause.getClass().getSimpleName(), cause);
            this.metric = metric;
            this.scope = scope;
        }
    }

    private static BigDecimal valueOrZero(BigDecimal v, LocalDate d, DashboardMetric m, MetricScope scope) {
        if (v == null) {
            log.info("看板指标无样本 date={} metric={} scope={}", d, m.key(), scope);
            return BigDecimal.ZERO;
        }
        return v;
    }
}

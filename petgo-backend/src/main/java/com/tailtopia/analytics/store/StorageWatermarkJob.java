package com.tailtopia.analytics.store;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsLockKeys;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.config.AnalyticsStorageProperties;
import com.tailtopia.analytics.store.StorageWatermarkPolicy.Evaluation;
import com.tailtopia.analytics.store.StorageWatermarkPolicy.Snapshot;
import com.tailtopia.analytics.store.StorageWatermarkPolicy.Thresholds;
import java.sql.Date;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 埋点库存储水位每日快照与告警（analytics-pipeline Story 1.10 AC1 / AC2，FR-16）。
 *
 * <ul>
 *   <li>每天 WIB 04:30（{@code analytics.storage.watermark-cron}；避开 02:00 擦除与 03:00 汇总；测试设 {@code -} 关闭，
 *       直接调 {@link #run(LocalDate, LocalDate)}）。</li>
 *   <li>采集（只统计<b>本环境连接的那个埋点库</b>，D-1，不跨库）：{@code pg_database_size(current_database())}；
 *       原始层 / 明细层 = 父表全部分区（{@code pg_inherits}，含 {@code events_raw_pdefault}）{@code pg_total_relation_size} 之和；
 *       {@code events_on_day} = 前一个 UTC 日原始层分区行数（分区不存在记 0）。</li>
 *   <li>{@code month_events} = 本 WIB 自然月此前各天 {@code events_on_day} 之和 + 当天；{@code month_growth_bytes} 见
 *       {@link StorageWatermarkPolicy#monthGrowthBytes}。{@code INSERT … ON CONFLICT (snapshot_date) DO UPDATE}，同一天重跑覆盖。
 *       日期全部由任务传入，SQL 里不用 {@code CURRENT_DATE}。</li>
 *   <li>拿 {@code pg_try_advisory_xact_lock(NAMESPACE, STORAGE_WATERMARK)}，拿不到直接返回。埋点事务用 Story 1.1 的
 *       {@code analyticsTransactionTemplate}。</li>
 *   <li>评估（{@link StorageWatermarkPolicy#evaluate}）后：超 40 GB → TECH 告警 {@code analytics:storage-over:<WIB日期>}
 *       （文案同时含「评估切回第三方」提示）；未超线但需提示 → {@code analytics:storage-switch-back:<WIB日期>}。按天去重，
 *       文案只有占用 / 事件量 / 月数，<b>不含任何用户数据</b>。</li>
 *   <li>失败只 WARN + TECH 告警（按天去重），绝不抛。</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(AnalyticsStorageProperties.class)
public class StorageWatermarkJob {

    private static final Logger log = LoggerFactory.getLogger(StorageWatermarkJob.class);

    static final String ALERT_OVER = "analytics:storage-over:";
    static final String ALERT_SWITCH_BACK = "analytics:storage-switch-back:";
    static final String ALERT_FAILED = "analytics:storage-watermark-failed:";

    static final String PARTITIONS_SIZE_SQL = "SELECT coalesce(sum(pg_total_relation_size(i.inhrelid)), 0) "
            + "FROM pg_inherits i WHERE i.inhparent = ?::text::regclass";

    static final String UPSERT_SQL = """
            INSERT INTO storage_watermarks (snapshot_date, db_bytes, raw_bytes, detail_bytes, events_on_day,
                                            month_events, month_growth_bytes)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (snapshot_date) DO UPDATE SET
                db_bytes = EXCLUDED.db_bytes,
                raw_bytes = EXCLUDED.raw_bytes,
                detail_bytes = EXCLUDED.detail_bytes,
                events_on_day = EXCLUDED.events_on_day,
                month_events = EXCLUDED.month_events,
                month_growth_bytes = EXCLUDED.month_growth_bytes,
                created_at = EXCLUDED.created_at""";

    /** 一次运行的结局。 */
    public enum Outcome { SKIPPED_DISABLED, SKIPPED_LOCKED, RECORDED, FAILED }

    /** 写入的快照。 */
    public record Watermark(LocalDate snapshotDate, long dbBytes, long rawBytes, long detailBytes, long eventsOnDay,
            long monthEvents, Long monthGrowthBytes) {
    }

    /** 运行结果：结局 + 快照（未记录时为空）+ 评估（未记录时为空）。 */
    public record RunResult(Outcome outcome, Watermark watermark, Evaluation evaluation) {
        static RunResult of(Outcome o) {
            return new RunResult(o, null, null);
        }
    }

    private final AnalyticsPipelineState state;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OpsAlertService alerts;
    private final AnalyticsStorageProperties props;
    private final String appEnv;
    private final Clock clock;

    @Autowired
    public StorageWatermarkJob(AnalyticsPipelineState state,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) TransactionTemplate analyticsTransactionTemplate,
            OpsAlertService alerts, AnalyticsStorageProperties props,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(state, analyticsJdbcTemplate, analyticsTransactionTemplate, alerts, props, appEnv, Clock.systemUTC());
    }

    /** 测试用：注入 mock 告警 / 自定阈值 / 固定时钟。 */
    StorageWatermarkJob(AnalyticsPipelineState state, JdbcTemplate jdbc, TransactionTemplate tx,
            OpsAlertService alerts, AnalyticsStorageProperties props, String appEnv, Clock clock) {
        this.state = state;
        this.jdbc = jdbc;
        this.tx = tx;
        this.alerts = alerts;
        this.props = props;
        this.appEnv = appEnv;
        this.clock = clock;
    }

    /** 每天 WIB 04:30。 */
    @Scheduled(cron = "${analytics.storage.watermark-cron:0 30 4 * * *}", zone = "Asia/Jakarta")
    public void scheduled() {
        Instant now = clock.instant();
        run(snapshotDateOf(now), eventsDayOf(now));
    }

    /** 快照日期 = 运行时刻的 WIB 日期。 */
    static LocalDate snapshotDateOf(Instant now) {
        return LocalDate.ofInstant(now, EventTimeCorrector.WIB);
    }

    /** 计数日 = 运行时刻 UTC 日期的前一天（已完整结束的 UTC 日；WIB 04:30 时 UTC 仍是前一天 21:30）。 */
    static LocalDate eventsDayOf(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1);
    }

    /**
     * 采集并写入一天的快照，然后评估、告警。可重复跑（同一天覆盖）。绝不抛。
     *
     * @param snapshotDate WIB 日期（主键）
     * @param eventsDay    计数的原始层 UTC 日
     */
    public RunResult run(LocalDate snapshotDate, LocalDate eventsDay) {
        try {
            // 库可用即跑：清单无效（CATALOG_INVALID）时库是好的，水位照样要看。
            if (!state.isEnabled() && state.reason().filter(r -> r == Reason.CATALOG_INVALID).isEmpty()) {
                return RunResult.of(Outcome.SKIPPED_DISABLED);
            }
            Watermark w;
            try {
                w = tx.execute(status -> collect(snapshotDate, eventsDay));
            } catch (RuntimeException e) {
                fail(snapshotDate, e);
                return RunResult.of(Outcome.FAILED);
            }
            if (w == null) {
                log.info("analytics storage watermark skipped: lock held elsewhere");
                return RunResult.of(Outcome.SKIPPED_LOCKED);
            }
            Evaluation ev = StorageWatermarkPolicy.evaluate(w.dbBytes(), w.monthEvents(), w.monthGrowthBytes(),
                    new Thresholds(props.alertBytes().toBytes(), props.monthEventsHint(), props.hintMonths()));
            log.info("analytics storage watermark recorded: date={} dbBytes={} rawBytes={} detailBytes={} "
                    + "eventsOnDay={} monthEvents={} monthGrowthBytes={} overAlert={} switchBackHint={}",
                    snapshotDate, w.dbBytes(), w.rawBytes(), w.detailBytes(), w.eventsOnDay(), w.monthEvents(),
                    w.monthGrowthBytes(), ev.overAlert(), ev.switchBackHint());
            if (ev.overAlert()) {
                send(ALERT_OVER + snapshotDate, overText(w, ev));
            } else if (ev.switchBackHint()) {
                send(ALERT_SWITCH_BACK + snapshotDate, hintText(w, ev));
            }
            return new RunResult(Outcome.RECORDED, w, ev);
        } catch (RuntimeException e) {
            fail(snapshotDate, e);
            return RunResult.of(Outcome.FAILED);
        }
    }

    /** 在调用方的埋点事务内：先拿锁，拿不到返回 {@code null}。 */
    private Watermark collect(LocalDate snapshotDate, LocalDate eventsDay) {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class,
                AnalyticsLockKeys.NAMESPACE, AnalyticsLockKeys.STORAGE_WATERMARK);
        if (!Boolean.TRUE.equals(locked)) {
            return null;
        }
        long dbBytes = nonNull(jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class));
        long rawBytes = nonNull(jdbc.queryForObject(PARTITIONS_SIZE_SQL, Long.class, RawPartitionPlanner.PARENT));
        long detailBytes = nonNull(jdbc.queryForObject(PARTITIONS_SIZE_SQL, Long.class, DetailPartitions.PARENT));
        long eventsOnDay = countRawPartition(eventsDay);

        LocalDate monthStart = snapshotDate.withDayOfMonth(1);
        long priorMonthEvents = nonNull(jdbc.queryForObject(
                "SELECT coalesce(sum(events_on_day), 0) FROM storage_watermarks "
                        + "WHERE snapshot_date >= ? AND snapshot_date < ?",
                Long.class, Date.valueOf(monthStart), Date.valueOf(snapshotDate)));
        long monthEvents = priorMonthEvents + eventsOnDay;

        List<Snapshot> prior = new ArrayList<>(2);
        prior.addAll(jdbc.query("SELECT snapshot_date, db_bytes FROM storage_watermarks WHERE snapshot_date <= ? "
                + "ORDER BY snapshot_date DESC LIMIT 1", (rs, i) -> new Snapshot(rs.getDate(1).toLocalDate(),
                rs.getLong(2)), Date.valueOf(snapshotDate.minusDays(StorageWatermarkPolicy.GROWTH_WINDOW_DAYS))));
        prior.addAll(jdbc.query("SELECT snapshot_date, db_bytes FROM storage_watermarks WHERE snapshot_date < ? "
                + "ORDER BY snapshot_date ASC LIMIT 1", (rs, i) -> new Snapshot(rs.getDate(1).toLocalDate(),
                rs.getLong(2)), Date.valueOf(snapshotDate)));
        Long growth = StorageWatermarkPolicy.monthGrowthBytes(snapshotDate, dbBytes, prior);

        jdbc.update(UPSERT_SQL, ps -> {
            ps.setDate(1, Date.valueOf(snapshotDate));
            ps.setLong(2, dbBytes);
            ps.setLong(3, rawBytes);
            ps.setLong(4, detailBytes);
            ps.setLong(5, eventsOnDay);
            ps.setLong(6, monthEvents);
            if (growth == null) {
                ps.setNull(7, Types.BIGINT);
            } else {
                ps.setLong(7, growth);
            }
        });
        return new Watermark(snapshotDate, dbBytes, rawBytes, detailBytes, eventsOnDay, monthEvents, growth);
    }

    /** 前一个 UTC 日原始层分区行数；分区不存在记 0。分区名经 {@link RawPartitionPlanner#partitionName} 校验后拼接。 */
    private long countRawPartition(LocalDate day) {
        String name = RawPartitionPlanner.partitionName(day);
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(?::text) IS NOT NULL", Boolean.class, name);
        if (!Boolean.TRUE.equals(exists)) {
            return 0;
        }
        return nonNull(jdbc.queryForObject("SELECT count(*) FROM " + name, Long.class));
    }

    private static long nonNull(Long v) {
        return v == null ? 0 : v;
    }

    private void fail(LocalDate snapshotDate, RuntimeException e) {
        String cause = e.getClass().getSimpleName();
        log.warn("analytics storage watermark failed: date={} cause={}", snapshotDate, cause);
        send(ALERT_FAILED + snapshotDate, "【埋点存储水位采集失败】环境 " + appEnv + "\n"
                + "日期（WIB）：" + snapshotDate + "；原因：" + cause + "\n"
                + "影响：今天没有水位快照、不做 40 GB 告警判断；停写熔断（50 GB）由每 5 分钟的独立检查负责，不受影响。当天只告警一次。\n"
                + "处理：检查埋点库可用性 / 写入账号权限；看后端日志 analytics storage watermark failed。任务可重复跑。");
    }

    private void send(String key, String text) {
        try {
            alerts.send(OpsChannel.TECH, key, text);
        } catch (RuntimeException e) {
            log.warn("analytics storage watermark alert failed: {}", e.getClass().getSimpleName());
        }
    }

    String overText(Watermark w, Evaluation ev) {
        return "【埋点库容量超线】环境 " + appEnv + "\n"
                + "日期（WIB）：" + w.snapshotDate() + "；埋点库占用 " + gb(w.dbBytes()) + "，已超过告警线 "
                + gb(props.alertBytes().toBytes()) + "（停写线 " + gb(props.stopBytes().toBytes()) + "，超过后接收端自动 429）。\n"
                + figures(w, ev)
                + "提示：请评估切回第三方（FR-16）。\n"
                + "处理：查 storage_watermarks 看增长来源；必要时手工删过期分区或缩短保留期（DELETE 不释放空间，须 DROP 分区）。";
    }

    String hintText(Watermark w, Evaluation ev) {
        return "【埋点库容量提示】环境 " + appEnv + "\n"
                + "日期（WIB）：" + w.snapshotDate() + "；埋点库占用 " + gb(w.dbBytes()) + "（告警线 "
                + gb(props.alertBytes().toBytes()) + "）。\n"
                + figures(w, ev)
                + "触发条件：本月事件量 ≥ " + props.monthEventsHint() + " 条，或按月增速外推 " + props.hintMonths()
                + " 个月内触及告警线。\n"
                + "提示：请评估切回第三方（FR-16）。";
    }

    private static String figures(Watermark w, Evaluation ev) {
        String months = ev.monthsToAlert() == null ? "不会触及（增速未知或不增长）"
                : String.format(Locale.ROOT, "%.1f 个月", ev.monthsToAlert());
        String growth = w.monthGrowthBytes() == null ? "未知（历史快照不足）" : gb(w.monthGrowthBytes()) + " / 30 天";
        return "本月（WIB 自然月）事件量：" + w.monthEvents() + " 条；月增长：" + growth + "；外推到告警线：" + months + "\n";
    }

    /** 字节 → GB（1024³，保留两位小数）。 */
    static String gb(long bytes) {
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

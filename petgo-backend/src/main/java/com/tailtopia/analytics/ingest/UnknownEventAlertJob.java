package com.tailtopia.analytics.ingest;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsLockKeys;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 未登记事件每日告警（analytics-pipeline Story 1.4 AC4）。
 *
 * <p>接收端对不在事件清单里的事件名<b>照样接收</b>、标 {@code unknown_event=true}。本任务每天 UTC 01:30
 * （{@code analytics.unknown-event-alert.cron}；测试设 {@code -} 关闭，直接调 {@link #run(LocalDate)}）统计<b>前一个 UTC 日</b>
 * 的未登记事件按事件名计数（前 50），有行就经 TECH 渠道告警一次（去重键带日期）。
 *
 * <ul>
 *   <li>用库里的数据计数而不是内存计数器：重启不丢、多次运行结果一致。</li>
 *   <li>拿 {@code pg_try_advisory_xact_lock(NAMESPACE, UNKNOWN_EVENT_ALERT)}，拿不到直接返回。</li>
 *   <li>日期由任务算好作为参数传入，SQL 里不用 {@code now()} / {@code CURRENT_DATE}（架构 §实现规范 4）。</li>
 *   <li>事件名不是用户数据，可以出现在告警文案里；日志只打日期与行数。</li>
 *   <li>告警在埋点事务返回后发（{@link OpsAlertService} 的 afterCommit 挂在业务库事务同步上）。失败只 WARN，绝不抛。</li>
 * </ul>
 */
@Component
public class UnknownEventAlertJob {

    private static final Logger log = LoggerFactory.getLogger(UnknownEventAlertJob.class);

    static final String ALERT_KEY = "analytics-unknown-events:";
    static final int TOP_N = 50;

    static final String QUERY = "SELECT event, count(*) AS n FROM events_raw "
            + "WHERE received_at >= ? AND received_at < ? AND unknown_event "
            + "GROUP BY event ORDER BY 2 DESC, 1 LIMIT " + TOP_N;

    /** 一个事件名的计数。 */
    public record EventCount(String event, long count) {
    }

    /** 一次运行的结局。 */
    public enum Outcome { SKIPPED_DISABLED, SKIPPED_LOCKED, NOTHING, ALERTED, FAILED }

    private final AnalyticsPipelineState state;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final Clock clock;

    @Autowired
    public UnknownEventAlertJob(AnalyticsPipelineState state,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) TransactionTemplate analyticsTransactionTemplate,
            OpsAlertService alerts,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(state, analyticsJdbcTemplate, analyticsTransactionTemplate, alerts, appEnv, Clock.systemUTC());
    }

    /** 测试用：注入固定时钟 / mock 告警。 */
    UnknownEventAlertJob(AnalyticsPipelineState state, JdbcTemplate jdbc, TransactionTemplate tx,
            OpsAlertService alerts, String appEnv, Clock clock) {
        this.state = state;
        this.jdbc = jdbc;
        this.tx = tx;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.clock = clock;
    }

    /** 每天 UTC 01:30，统计前一个 UTC 日。 */
    @Scheduled(cron = "${analytics.unknown-event-alert.cron:0 30 1 * * *}", zone = "UTC")
    public void scheduled() {
        run(clock.instant().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1));
    }

    /**
     * 统计某个 UTC 日的未登记事件并告警。
     *
     * @param day UTC 日期
     */
    public Outcome run(LocalDate day) {
        // 库可用即跑：清单无效（CATALOG_INVALID）时接收端已 503，但库是好的，之前收下的照样能统计。
        if (!state.isEnabled() && state.reason().filter(r -> r == Reason.CATALOG_INVALID).isEmpty()) {
            return Outcome.SKIPPED_DISABLED;
        }
        List<EventCount> rows;
        try {
            rows = tx.execute(status -> query(day));
        } catch (RuntimeException e) {
            log.warn("analytics unknown-event alert failed: day={} cause={}", day, e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
        if (rows == null) {
            log.info("analytics unknown-event alert skipped: lock held elsewhere");
            return Outcome.SKIPPED_LOCKED;
        }
        if (rows.isEmpty()) {
            return Outcome.NOTHING;
        }
        log.info("analytics unknown events found: day={} distinctEvents={}", day, rows.size());
        try {
            alerts.send(OpsChannel.TECH, ALERT_KEY + day, text(day, rows));
        } catch (RuntimeException e) {
            log.warn("analytics unknown-event alert send failed: {}", e.getClass().getSimpleName());
        }
        return Outcome.ALERTED;
    }

    /** 在调用方的埋点事务内：先拿锁，拿不到返回 {@code null}。 */
    private List<EventCount> query(LocalDate day) {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class,
                AnalyticsLockKeys.NAMESPACE, AnalyticsLockKeys.UNKNOWN_EVENT_ALERT);
        if (!Boolean.TRUE.equals(locked)) {
            return null;
        }
        OffsetDateTime from = day.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = from.plusDays(1);
        return jdbc.query(QUERY, (rs, i) -> new EventCount(rs.getString(1), rs.getLong(2)), from, to);
    }

    String text(LocalDate day, List<EventCount> rows) {
        StringBuilder sb = new StringBuilder("【埋点未登记事件】环境 ").append(appEnv).append('\n')
                .append("日期（UTC）：").append(day).append("，共 ").append(rows.size()).append(" 个事件名")
                .append(rows.size() >= TOP_N ? "（只列前 " + TOP_N + " 个）" : "").append('\n');
        for (EventCount r : rows) {
            sb.append("· ").append(printable(r.event())).append("：").append(r.count()).append('\n');
        }
        sb.append("含义：这些事件已照常入库（unknown_event=true），但不在 event-catalog.yaml 里，明细层 / 指标不认。\n")
                .append("处理：确认是新事件就按「登记 → 加 Dart 常量 → 比对测试」三步补登记；是拼错就修 App 埋点。");
        return sb.toString();
    }

    /** 事件名是客户端原样上报的：控制字符换成 {@code ?}，免得打乱告警排版。 */
    private static String printable(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> out.appendCodePoint(Character.isISOControl(cp) ? '?' : cp));
        return out.toString();
    }
}

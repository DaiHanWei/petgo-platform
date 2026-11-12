package com.tailtopia.analytics.ingest;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.config.AnalyticsStorageProperties;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 埋点接收停写熔断（analytics-pipeline Story 1.10 AC3，架构「磁盘熔断」）：埋点库整库占用超过
 * {@code analytics.storage.stop-bytes}（默认 50 GB）→ 打开，接收端返回 429 + {@code Retry-After}；降回阈值及以下 → 自动关闭。
 *
 * <ul>
 *   <li>每 {@code analytics.storage.breaker-check-ms}（默认 5 分钟）检查一次，启动即检查一次（首延默认 0；
 *       集成测试基类把首延拉到 1 天，由用例直接调 {@link #check(long)}）。</li>
 *   <li>检查只做一次 {@code SELECT pg_database_size(current_database())}（只查本环境连接的埋点库，D-1）。查询失败 →
 *       <b>保持原状态</b>、只 WARN（埋点库不可用由 Story 1.1 的管道停用逻辑处理，这里不重复告警）。管道因库不可用 / 迁移失败
 *       停用时直接跳过（接收端此时已 503）。</li>
 *   <li>状态切换时经 TECH 渠道告警一次（去重键带 UTC 时间到分钟）；不切换不告警。文案只有占用与阈值，不含任何用户数据。</li>
 *   <li>判定点在 {@link IngestQueue} 的入队门（{@link IngestQueue#isWriteStopped()}）：接收端与 Story 1.9 的
 *       {@code SelfHostedAnalyticsClient} 都经此被拒（后者计入丢弃）。<b>只拦新写入</b>：已在内存队列里的事件照常由
 *       {@link IngestFlusher} 写完；明细转换、分区维护、擦除不受影响（{@code DROP} 分区正是降水位的手段）。</li>
 * </ul>
 *
 * <p>状态迁移是纯函数 {@link #next}，L0 直接测；占用读取可注入（{@link LongSupplier}，失败抛 {@link RuntimeException}）。
 */
@Component
@EnableConfigurationProperties(AnalyticsStorageProperties.class)
public class IngestCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(IngestCircuitBreaker.class);

    static final String ALERT_OPEN = "analytics:breaker-open:";
    static final String ALERT_CLOSED = "analytics:breaker-closed:";
    static final String SIZE_SQL = "SELECT pg_database_size(current_database())";
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm");

    /** 熔断状态。 */
    public enum State {
        /** 正常接收。 */
        CLOSED,
        /** 停写：接收端 429。 */
        OPEN
    }

    /** 一次迁移的结果。 */
    public record Transition(State state, boolean changed) {
    }

    /** 一次检查的结局。 */
    public enum Outcome { SKIPPED_DISABLED, FAILED, UNCHANGED, OPENED, CLOSED }

    private final AnalyticsPipelineState pipeline;
    private final LongSupplier dbBytesReader;
    private final OpsAlertService alerts;
    private final AnalyticsStorageProperties props;
    private final String appEnv;
    private final Clock clock;

    private volatile State state = State.CLOSED;

    @Autowired
    public IngestCircuitBreaker(AnalyticsPipelineState pipeline,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            OpsAlertService alerts, AnalyticsStorageProperties props,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(pipeline, () -> {
            Long n = analyticsJdbcTemplate.queryForObject(SIZE_SQL, Long.class);
            if (n == null) {
                throw new IllegalStateException("pg_database_size returned null");
            }
            return n;
        }, alerts, props, appEnv, Clock.systemUTC());
    }

    /** 测试用：注入假的占用读取函数、mock 告警、固定时钟。 */
    IngestCircuitBreaker(AnalyticsPipelineState pipeline, LongSupplier dbBytesReader, OpsAlertService alerts,
            AnalyticsStorageProperties props, String appEnv, Clock clock) {
        this.pipeline = pipeline;
        this.dbBytesReader = dbBytesReader;
        this.alerts = alerts;
        this.props = props;
        this.appEnv = appEnv;
        this.clock = clock;
    }

    /**
     * 纯状态迁移：{@code CLOSED} 且 {@code dbBytes > stopBytes} → {@code OPEN}；{@code OPEN} 且 {@code dbBytes <= stopBytes}
     * → {@code CLOSED}；其他保持。
     */
    public static Transition next(State current, long dbBytes, long stopBytes) {
        if (current == State.CLOSED && dbBytes > stopBytes) {
            return new Transition(State.OPEN, true);
        }
        if (current == State.OPEN && dbBytes <= stopBytes) {
            return new Transition(State.CLOSED, true);
        }
        return new Transition(current, false);
    }

    /** 接收侧读取：是否停写。 */
    public boolean isOpen() {
        return state == State.OPEN;
    }

    /** 当前状态。 */
    public State state() {
        return state;
    }

    /** 熔断时 429 的 {@code Retry-After}（秒）。 */
    public int retryAfterSeconds() {
        return props.breakerRetryAfterSeconds();
    }

    @Scheduled(fixedDelayString = "${analytics.storage.breaker-check-ms:300000}",
            initialDelayString = "${analytics.storage.breaker-initial-delay-ms:0}")
    public void scheduled() {
        check(props.stopBytes().toBytes());
    }

    /**
     * 检查一次并按需切换 + 告警。绝不抛。
     *
     * @param stopBytes 停写阈值（字节）。定时任务传配置值；L1 测试直接传 1 / 大值验证接入。
     */
    synchronized Outcome check(long stopBytes) {
        try {
            if (!pipelineUsable()) {
                return Outcome.SKIPPED_DISABLED;
            }
            long dbBytes;
            try {
                dbBytes = dbBytesReader.getAsLong();
            } catch (RuntimeException e) {
                log.warn("analytics breaker check failed, keeping state: state={} cause={}", state,
                        e.getClass().getSimpleName());
                return Outcome.FAILED;
            }
            Transition t = next(state, dbBytes, stopBytes);
            if (!t.changed()) {
                return Outcome.UNCHANGED;
            }
            state = t.state();
            boolean opened = t.state() == State.OPEN;
            log.warn("analytics ingest breaker {}: dbBytes={} stopBytes={}", opened ? "OPEN" : "CLOSED", dbBytes,
                    stopBytes);
            alert(opened, dbBytes, stopBytes);
            return opened ? Outcome.OPENED : Outcome.CLOSED;
        } catch (RuntimeException e) {
            log.warn("analytics breaker check error: {}", e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    /** 库可用即检查：清单无效（CATALOG_INVALID）时库是好的，照常检查。 */
    private boolean pipelineUsable() {
        return pipeline.isEnabled() || pipeline.reason().filter(r -> r == Reason.CATALOG_INVALID).isPresent();
    }

    private void alert(boolean opened, long dbBytes, long stopBytes) {
        String minute = MINUTE.format(clock.instant().atZone(ZoneOffset.UTC));
        String text;
        if (opened) {
            text = "【埋点停写熔断】环境 " + appEnv + "\n"
                    + "埋点库占用 " + gb(dbBytes) + " 已超过停写线 " + gb(stopBytes) + "，接收端改为返回 429（Retry-After "
                    + props.breakerRetryAfterSeconds() + " 秒），服务端事件丢弃计数；内存队列里已有的事件照常写完。\n"
                    + "处理：查 storage_watermarks 看增长来源 → 手工删过期分区或缩短保留期（DELETE 不释放空间，须 DROP 分区）"
                    + " → 降回停写线以下后 5 分钟内自动恢复。见 docs/runbooks/runbook-analytics-pipeline.md。";
        } else {
            text = "【埋点停写恢复】环境 " + appEnv + "\n"
                    + "埋点库占用 " + gb(dbBytes) + " 已降回停写线 " + gb(stopBytes) + " 及以下，接收端恢复正常接收。";
        }
        try {
            alerts.send(OpsChannel.TECH, (opened ? ALERT_OPEN : ALERT_CLOSED) + minute, text);
        } catch (RuntimeException e) {
            log.warn("analytics breaker alert failed: {}", e.getClass().getSimpleName());
        }
    }

    /** 字节 → GB（1024³，保留两位小数）。 */
    static String gb(long bytes) {
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

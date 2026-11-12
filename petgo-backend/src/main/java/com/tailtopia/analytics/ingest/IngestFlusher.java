package com.tailtopia.analytics.ingest;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 埋点队列写库线程（analytics-pipeline Story 1.4 AC7 / AC8）：从 {@link IngestQueue} 每 1 秒或攒够 500 条取一批，
 * 用埋点库 {@code JdbcTemplate.batchUpdate} 写父表 {@code events_raw}（PG 按 {@code received_at} 路由到日分区，缺分区落 DEFAULT 兜底）。
 *
 * <h2>为什么是 {@link SmartLifecycle} + 自带线程</h2>
 * 不用 {@code @Scheduled}：全局调度池只有 4 个线程，而这里是长驻循环。本类自带<b>一个</b>专用守护线程。
 *
 * <h2>停机顺序（AC8）</h2>
 * Spring 按 phase <b>从大到小</b>停止。Boot 4.0.6 源码核实：{@code WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE}
 * = {@code DEFAULT_PHASE - 1024}（优雅停机，等在途请求处理完），{@code WebServerStartStopLifecycle#getPhase}
 * = {@code DEFAULT_PHASE - 2048}（停 Web 服务器）。本类取 {@link #PHASE} = {@code DEFAULT_PHASE - 4096}，
 * 即<b>先停接收、再写完队列</b>。{@link #stop()}：关闭队列（之后 controller 返回 503、入队一律拒绝）→ 把队列写完 → 线程退出；
 * 最多等 {@code analytics.ingest.shutdown-drain-timeout}（默认 20s，小于 Spring 默认每阶段 30s），超时把剩余条数打 WARN。
 * 数据源在生命周期停止之后才销毁，写库可用。
 *
 * <h2>写失败</h2>
 * 同一批在一个埋点事务里写（全成或全不成，重试不会产生半批重复）；失败按 1s / 2s / 4s 重试 3 次，仍失败 → 丢弃、计数、
 * WARN（只打条数与首末 {@code messageId}）、TECH 告警（按 UTC 日去重）。不无限重试，免得堵死队列。
 *
 * <p>埋点库只经 {@code @Qualifier("analytics")} 的 {@link JdbcTemplate} / {@link TransactionTemplate} 访问，禁止 JPA。
 */
@Component
public class IngestFlusher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IngestFlusher.class);

    /** 晚于 Web 服务器优雅停机（-1024）与停止（-2048）。 */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    static final int MAX_RETRIES = 3;
    static final String ALERT_WRITE_FAILED = "analytics-ingest-write-failed:";

    static final String INSERT_SQL = "INSERT INTO events_raw (message_id, event, client_ts, sent_at, received_at, "
            + "anonymous_id, user_key, user_id, source, properties, context, identity_unverified, unknown_event) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)";

    private final IngestQueue queue;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final int batchSize;
    private final Duration interval;
    private final Duration drainTimeout;
    private final Duration retryBackoff;
    private final Clock clock;

    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong writtenCount = new AtomicLong();
    private final Object lifecycleMonitor = new Object();
    private volatile Thread worker;

    @Autowired
    public IngestFlusher(IngestQueue queue,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) TransactionTemplate analyticsTransactionTemplate,
            OpsAlertService alerts, IngestProperties props,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(queue, analyticsJdbcTemplate, analyticsTransactionTemplate, alerts, appEnv, props.flushBatchSize(),
                Duration.ofMillis(props.flushIntervalMs()), props.shutdownDrainTimeout(), Duration.ofSeconds(1),
                Clock.systemUTC());
    }

    /** 测试用：可调批量、间隔、停机等待、重试退避基数。 */
    IngestFlusher(IngestQueue queue, JdbcTemplate jdbc, TransactionTemplate tx, OpsAlertService alerts,
            String appEnv, int batchSize, Duration interval, Duration drainTimeout, Duration retryBackoff,
            Clock clock) {
        this.queue = queue;
        this.jdbc = jdbc;
        this.tx = tx;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.batchSize = Math.max(1, batchSize);
        this.interval = interval;
        this.drainTimeout = drainTimeout;
        this.retryBackoff = retryBackoff;
        this.clock = clock;
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (worker != null || queue.isClosed()) {
                return;
            }
            Thread t = new Thread(this::loop, "analytics-ingest-flusher");
            t.setDaemon(true);
            worker = t;
            t.start();
        }
    }

    /** 先停接收（关闭队列），再把队列写完，最多等 {@code drainTimeout}。 */
    @Override
    public void stop() {
        Thread t;
        synchronized (lifecycleMonitor) {
            t = worker;
            queue.close();
            if (t == null) {
                return;
            }
        }
        try {
            t.join(Math.max(1, drainTimeout.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            log.warn("analytics ingest flusher drain timed out: remaining={}", queue.size());
            t.interrupt();
        } else {
            log.info("analytics ingest flusher stopped: written={} dropped={}", writtenCount.get(),
                    droppedCount.get());
        }
        synchronized (lifecycleMonitor) {
            worker = null;
        }
    }

    @Override
    public boolean isRunning() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** 累计因写库失败丢弃的条数。 */
    public long droppedCount() {
        return droppedCount.get();
    }

    /** 累计写入条数。 */
    public long writtenCount() {
        return writtenCount.get();
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            boolean closing = queue.isClosed();
            List<RawEvent> batch;
            try {
                batch = queue.drain(batchSize, closing ? Duration.ZERO : interval);
            } catch (RuntimeException e) {
                log.warn("analytics ingest drain failed: {}", e.getClass().getSimpleName());
                continue;
            }
            if (!batch.isEmpty()) {
                write(batch);
            } else if (closing) {
                return;
            }
        }
    }

    /** 写一批：埋点事务内 {@code batchUpdate}，失败按 1s / 2s / 4s 重试 3 次，仍失败丢弃 + 告警。绝不抛。 */
    void write(List<RawEvent> batch) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0 && !sleep(retryBackoff.multipliedBy(1L << (attempt - 1)))) {
                break;
            }
            try {
                tx.executeWithoutResult(status -> jdbc.batchUpdate(INSERT_SQL, batch, batch.size(), IngestFlusher::bind));
                writtenCount.addAndGet(batch.size());
                return;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        long total = droppedCount.addAndGet(batch.size());
        String cause = last == null ? "Interrupted" : last.getClass().getSimpleName();
        log.warn("analytics ingest write failed, dropped: count={} firstMessageId={} lastMessageId={} cause={} "
                + "droppedTotal={}", batch.size(), batch.get(0).messageId(), batch.get(batch.size() - 1).messageId(),
                cause, total);
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        try {
            alerts.send(OpsChannel.TECH, ALERT_WRITE_FAILED + today, "【埋点写库失败】环境 " + appEnv + "\n"
                    + "本批丢弃：" + batch.size() + " 条（重试 " + MAX_RETRIES + " 次后放弃）；原因：" + cause + "\n"
                    + "影响：这批事件已返回 202，不会重发，永久丢失；后续批次照常尝试写入。当天只告警一次。\n"
                    + "处理：检查埋点库可用性、写入账号权限 / 连接数、磁盘；看后端日志 analytics ingest write failed。");
        } catch (RuntimeException e) {
            log.warn("analytics ingest alert failed: {}", e.getClass().getSimpleName());
        }
    }

    /** @return {@code false} = 被中断（停机超时），放弃重试 */
    private static boolean sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static void bind(PreparedStatement ps, RawEvent e) throws SQLException {
        ps.setString(1, e.messageId());
        ps.setString(2, e.event());
        ps.setObject(3, utc(e.clientTs()));
        ps.setObject(4, utc(e.sentAt()));
        ps.setObject(5, utc(e.receivedAt()));
        ps.setString(6, e.anonymousId());
        ps.setString(7, e.userKey());
        if (e.userId() == null) {
            ps.setNull(8, Types.BIGINT);
        } else {
            ps.setLong(8, e.userId());
        }
        ps.setString(9, e.source().name());
        ps.setString(10, e.propertiesJson());
        ps.setString(11, e.contextJson());
        ps.setBoolean(12, e.identityUnverified());
        ps.setBoolean(13, e.unknownEvent());
    }

    private static OffsetDateTime utc(Instant t) {
        return t == null ? null : OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}

package com.tailtopia.analytics.ingest;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.identity.IdentityLink;
import com.tailtopia.analytics.identity.IdentityLinkRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * <h2>身份关联（Story 1.5）</h2>
 * 带 {@link RawEvent#identityLink()} 的事件，其关联行在<b>同一个埋点事务</b>里、写完 {@code events_raw} 之后
 * 由 {@link IdentityLinkRepository#appendAll} 追加（{@code ON CONFLICT DO NOTHING}，重试幂等）——事件和关联同生共死；
 * 请求线程不写库。本批没有关联时只有一条语句。
 *
 * <h2>写失败</h2>
 * 同一批在一个埋点事务里写（全成或全不成，重试不会产生半批重复）；失败按 1s / 2s / 4s 重试 3 次，仍失败 → 丢弃、计数、
 * WARN（只打条数与首末 {@code messageId}）、TECH 告警（按 UTC 日去重）。不无限重试，免得堵死队列。
 *
 * <h2>线程存活（Story 4.9 AC1 / AC2）</h2>
 * 循环每一轮都 {@code catch Throwable}（含 {@code OutOfMemoryError} 等 {@link Error}）：ERROR 日志只记异常类名和消息（不含事件内容）、
 * 事务外 TECH 告警（按 UTC 日去重 {@code analytics-ingest-flusher-error:<日>}）、退避 1 秒后继续下一轮。写库过程中遇到
 * {@link Error} 时当前这批按丢弃处理（计入 {@link #droppedCount()}），<b>不重试</b>，避免同一批反复触发 OOM。
 * 每次 drain 返回（有没有数据都算）更新 {@link #lastProgressAt()}；{@link IngestFlusherWatchdog} 据此判停滞，并在线程不在时调
 * {@link #restartIfDead()} 重启。
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
    static final String ALERT_FLUSHER_ERROR = "analytics-ingest-flusher-error:";
    static final String THREAD_NAME = "analytics-ingest-flusher";

    static final String INSERT_SQL = "INSERT INTO events_raw (message_id, event, client_ts, sent_at, received_at, "
            + "anonymous_id, user_key, user_id, source, properties, context, identity_unverified, unknown_event) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)";

    private final IngestQueue queue;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final IdentityLinkRepository identityLinks;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final int batchSize;
    private final Duration interval;
    private final Duration drainTimeout;
    private final Duration retryBackoff;
    private final Duration errorBackoff;
    private final Clock clock;

    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong writtenCount = new AtomicLong();
    private final Object lifecycleMonitor = new Object();
    private volatile Thread worker;
    /** 生命周期已启动且未停止（与线程存活无关）。 */
    private volatile boolean started;
    /** 最近一次 drain 返回的时刻（null = 线程从未启动）。 */
    private volatile Instant lastProgressAt;

    @Autowired
    public IngestFlusher(IngestQueue queue,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) TransactionTemplate analyticsTransactionTemplate,
            IdentityLinkRepository identityLinks, OpsAlertService alerts, IngestProperties props,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(queue, analyticsJdbcTemplate, analyticsTransactionTemplate, identityLinks, alerts, appEnv,
                props.flushBatchSize(), Duration.ofMillis(props.flushIntervalMs()), props.shutdownDrainTimeout(),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Clock.systemUTC());
    }

    /** 测试用：可调批量、间隔、停机等待、重试退避基数；关联仓库用同一个 {@code jdbc}。 */
    IngestFlusher(IngestQueue queue, JdbcTemplate jdbc, TransactionTemplate tx, OpsAlertService alerts,
            String appEnv, int batchSize, Duration interval, Duration drainTimeout, Duration retryBackoff,
            Clock clock) {
        this(queue, jdbc, tx, new IdentityLinkRepository(jdbc), alerts, appEnv, batchSize, interval, drainTimeout,
                retryBackoff, retryBackoff, clock);
    }

    IngestFlusher(IngestQueue queue, JdbcTemplate jdbc, TransactionTemplate tx, IdentityLinkRepository identityLinks,
            OpsAlertService alerts, String appEnv, int batchSize, Duration interval, Duration drainTimeout,
            Duration retryBackoff, Duration errorBackoff, Clock clock) {
        this.queue = queue;
        this.jdbc = jdbc;
        this.tx = tx;
        this.identityLinks = identityLinks;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.batchSize = Math.max(1, batchSize);
        this.interval = interval;
        this.drainTimeout = drainTimeout;
        this.retryBackoff = retryBackoff;
        this.errorBackoff = errorBackoff;
        this.clock = clock;
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (worker != null || queue.isClosed()) {
                return;
            }
            started = true;
            spawn();
        }
    }

    /** 调用方持有 {@link #lifecycleMonitor}。 */
    private void spawn() {
        Thread t = new Thread(this::loop, THREAD_NAME);
        t.setDaemon(true);
        lastProgressAt = clock.instant();
        worker = t;
        t.start();
    }

    /**
     * 看门狗用（Story 4.9 AC2）：生命周期已启动、线程已不在（意外终止）且不在停机过程中 → 重起一个线程。
     * 与 {@link #start()} / {@link #stop()} 同一把锁，不会起出两个线程，也不会在停机时复活。
     *
     * @return {@code true} = 本次确实重启了
     */
    public boolean restartIfDead() {
        synchronized (lifecycleMonitor) {
            if (!started || queue.isClosed()) {
                return false;
            }
            Thread t = worker;
            if (t != null && t.isAlive()) {
                return false;
            }
            spawn();
            return true;
        }
    }

    /** 是否在停机过程中（队列已关闭）：看门狗此时不重启、不判停滞。 */
    public boolean isStopping() {
        return queue.isClosed();
    }

    /** 测试用：当前写库线程（可能为 null / 已终止）。 */
    Thread worker() {
        return worker;
    }

    /** 内存队列当前条数（只用于日志）。 */
    public int queuedEvents() {
        return queue.size();
    }

    /** 最近一次 drain 返回的时刻；线程从未启动为 {@code null}。 */
    public Instant lastProgressAt() {
        return lastProgressAt;
    }

    /** 先停接收（关闭队列），再把队列写完，最多等 {@code drainTimeout}。 */
    @Override
    public void stop() {
        Thread t;
        synchronized (lifecycleMonitor) {
            queue.close();
            if (!started) {
                return;
            }
            t = worker;
            if (t == null || !t.isAlive()) {
                // Story 4.9：写库线程意外终止过且看门狗还没来得及重启 —— 补一个线程把队列写完（关闭后 loop 写空即退出）
                spawn();
                t = worker;
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
            started = false;
        }
    }

    /**
     * 生命周期状态：{@link #start()} 之后、{@link #stop()} 完成之前为 true —— <b>与写库线程是否活着无关</b>（Story 4.9）。
     * 若按线程存活返回，线程意外终止后 Spring 停机时会跳过 {@link #stop()}，队列既不关闭也不写完。线程存活看
     * {@link #isWorkerAlive()}。
     */
    @Override
    public boolean isRunning() {
        return started;
    }

    /** 写库线程是否活着（看门狗用，Story 4.9 AC2）。 */
    public boolean isWorkerAlive() {
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
            try {
                if (!runRound()) {
                    return;
                }
            } catch (Throwable t) {
                // Story 4.9 AC1：含 Error（OOM 等）在内一律不让线程退出；本批已在 write() 里按丢弃计数
                onLoopError(t);
                if (!sleep(errorBackoff)) {
                    return;
                }
            }
        }
    }

    /**
     * 一轮：drain → 写。
     *
     * @return {@code false} = 停机且队列已空，线程退出
     */
    boolean runRound() {
        boolean closing = queue.isClosed();
        List<RawEvent> batch = queue.drain(batchSize, closing ? Duration.ZERO : interval);
        lastProgressAt = clock.instant();
        if (!batch.isEmpty()) {
            write(batch);
            return true;
        }
        return !closing;
    }

    /** 循环异常：ERROR（只记类名和消息）+ 事务外 TECH 告警（UTC 日去重）。自身绝不抛。 */
    void onLoopError(Throwable t) {
        try {
            log.error("analytics ingest flusher round failed, backing off: cause={} message={}",
                    t.getClass().getName(), shortMessage(t));
            LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
            try {
                alerts.send(OpsChannel.TECH, ALERT_FLUSHER_ERROR + today, "【埋点写库线程异常】环境 " + appEnv + "\n"
                        + "原因：" + t.getClass().getSimpleName() + "（写库线程未退出，退避 " + errorBackoff.toSeconds()
                        + " 秒后继续）\n"
                        + "影响：若异常发生在写库时，当前这批已丢弃（不重试，避免同一批反复触发）；其余批次照常写入。当天只告警一次。\n"
                        + "处理：看后端日志 analytics ingest flusher round failed；若为 OutOfMemoryError，检查堆大小与"
                        + " analytics.ingest.queue-max-bytes。见 docs/runbooks/runbook-analytics-pipeline.md。");
            } catch (RuntimeException e) {
                log.warn("analytics ingest alert failed: {}", e.getClass().getSimpleName());
            }
        } catch (Throwable ignored) {
            // 连记日志都失败（极端 OOM）：什么也不做，交给退避后的下一轮
        }
    }

    /** 写一批：埋点事务内先 {@code events_raw} 再关联，失败按 1s / 2s / 4s 重试 3 次，仍失败丢弃 + 告警。绝不抛。 */
    void write(List<RawEvent> batch) {
        boolean[] counted = {false};
        try {
            writeAndCount(batch, counted);
        } catch (Error e) {
            // Story 4.9 AC1：Error（OOM 等）—— 本批按丢弃计数（若还没计过）、不重试，交给 loop 打 ERROR / 告警 / 退避
            if (!counted[0]) {
                long total = droppedCount.addAndGet(batch.size());
                log.warn("analytics ingest write aborted by error, dropped: count={} firstMessageId={} "
                        + "lastMessageId={} cause={} droppedTotal={}", batch.size(), batch.get(0).messageId(),
                        batch.get(batch.size() - 1).messageId(), e.getClass().getSimpleName(), total);
            }
            throw e;
        }
    }

    /** {@link #write} 的主体；本批一旦计入写入 / 丢弃数就置 {@code counted[0]}，免得 Error 路径重复计数。 */
    private void writeAndCount(List<RawEvent> batch, boolean[] counted) {
        List<IdentityLink> links = linksOf(batch);
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0 && !sleep(retryBackoff.multipliedBy(1L << (attempt - 1)))) {
                break;
            }
            try {
                tx.executeWithoutResult(status -> {
                    jdbc.batchUpdate(INSERT_SQL, batch, batch.size(), IngestFlusher::bind);
                    if (!links.isEmpty()) {
                        identityLinks.appendAll(links);
                    }
                });
                writtenCount.addAndGet(batch.size());
                counted[0] = true;
                return;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        long total = droppedCount.addAndGet(batch.size());
        counted[0] = true;
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

    /** 异常消息截到 200 字符（只会是 Error / 内存队列异常的消息；写库的 RuntimeException 在 write() 内已吞掉）。 */
    static String shortMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.length() <= 200 ? m : m.substring(0, 200);
    }

    /** 本批事件挂的关联行（按事件顺序）；没有返回空列表。 */
    static List<IdentityLink> linksOf(List<RawEvent> batch) {
        List<IdentityLink> links = new ArrayList<>();
        for (RawEvent e : batch) {
            if (e.identityLink() != null) {
                links.add(e.identityLink());
            }
        }
        return links;
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

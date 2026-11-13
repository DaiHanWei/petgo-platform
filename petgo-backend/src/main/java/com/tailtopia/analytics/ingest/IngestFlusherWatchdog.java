package com.tailtopia.analytics.ingest;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 埋点写库线程看门狗（analytics-pipeline Story 4.9 AC2）：每 {@code analytics.ingest.watchdog-interval}（默认 60s）检查一次
 * {@link IngestFlusher}。
 *
 * <ul>
 *   <li>管道停用（{@link AnalyticsPipelineState} 未启用）或正在停机 → 跳过。</li>
 *   <li>线程不在了（{@link IngestFlusher#isWorkerAlive()} 为 false；story 原文写 {@code !isRunning()}，但
 *       {@code isRunning()} 现在表示生命周期状态，见其注释）→ {@link IngestFlusher#restartIfDead()} 重启，并 TECH 告警
 *       {@code analytics-ingest-flusher-restarted:<UTC 日>}。</li>
 *   <li>线程还在，但 {@link IngestFlusher#lastProgressAt()} 超过 {@code analytics.ingest.stall-threshold}（默认 5m）未更新 →
 *       TECH 告警 {@code analytics-ingest-flusher-stalled:<UTC 日>}，<b>不重启</b>（多半卡在埋点库上，重启解决不了，还可能写出重复批）。</li>
 * </ul>
 * 只做内存检查，不碰库、不占连接；告警在事务外调用，外层 {@code catch RuntimeException}，绝不抛。
 * 调度由 {@code AnalyticsSchedulingConfig} 按 {@link IngestProperties#watchdogInterval()} 编程式注册（写法参照
 * {@link IngestCircuitBreaker}；键名、默认值同 story，非法值回退默认，不让启动失败）。
 * 写库线程问题靠重启和告警处理，不把整条管道停用（不改 {@code AnalyticsPipelineState.Reason}）。
 */
@Component
public class IngestFlusherWatchdog {

    private static final Logger log = LoggerFactory.getLogger(IngestFlusherWatchdog.class);

    static final String ALERT_RESTARTED = "analytics-ingest-flusher-restarted:";
    static final String ALERT_STALLED = "analytics-ingest-flusher-stalled:";

    /** 一次检查的结局。 */
    public enum Outcome {
        /** 管道停用或停机中。 */
        SKIPPED,
        /** 线程在、有进展。 */
        HEALTHY,
        /** 线程不在，已重启。 */
        RESTARTED,
        /** 线程在，但超过阈值没有进展。 */
        STALLED
    }

    private final IngestFlusher flusher;
    private final AnalyticsPipelineState pipeline;
    private final OpsAlertService alerts;
    private final Duration stallThreshold;
    private final String appEnv;
    private final Clock clock;

    @Autowired
    public IngestFlusherWatchdog(IngestFlusher flusher, AnalyticsPipelineState pipeline, OpsAlertService alerts,
            IngestProperties props, @Value("${analytics.app-env:dev}") String appEnv) {
        this(flusher, pipeline, alerts, props.stallThreshold(), appEnv, Clock.systemUTC());
    }

    /** 测试用：注入时钟、mock 告警。 */
    IngestFlusherWatchdog(IngestFlusher flusher, AnalyticsPipelineState pipeline, OpsAlertService alerts,
            Duration stallThreshold, String appEnv, Clock clock) {
        this.flusher = flusher;
        this.pipeline = pipeline;
        this.alerts = alerts;
        this.stallThreshold = stallThreshold;
        this.appEnv = appEnv;
        this.clock = clock;
    }

    /** 定时入口（由 {@code AnalyticsSchedulingConfig} 注册）。 */
    public void scheduled() {
        check();
    }

    /** 检查一次。绝不抛。 */
    public Outcome check() {
        try {
            if (!pipeline.isEnabled() || flusher.isStopping()) {
                return Outcome.SKIPPED;
            }
            LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
            if (!flusher.isWorkerAlive()) {
                if (!flusher.restartIfDead()) {
                    // 并发：刚被别处启动，或刚进入停机
                    return Outcome.SKIPPED;
                }
                log.error("analytics ingest flusher thread was dead, restarted: queued={}", flusher.queuedEvents());
                alert(ALERT_RESTARTED + today, "【埋点写库线程已重启】环境 " + appEnv + "\n"
                        + "写库线程意外终止，看门狗已重启。\n"
                        + "影响：线程不在期间接收照常返回 202、事件堆在内存队列（上限 analytics.ingest.queue-max-bytes，满了 429），重启后继续写入；"
                        + "若期间后端停机，队列里的事件会丢失。当天只告警一次。\n"
                        + "处理：看后端日志 analytics ingest flusher 前后的 ERROR，确认终止原因（OOM 等）。"
                        + "见 docs/runbooks/runbook-analytics-pipeline.md。");
                return Outcome.RESTARTED;
            }
            Instant last = flusher.lastProgressAt();
            if (last != null && Duration.between(last, clock.instant()).compareTo(stallThreshold) > 0) {
                long idleSeconds = Duration.between(last, clock.instant()).toSeconds();
                log.warn("analytics ingest flusher stalled: idleSeconds={} thresholdSeconds={}", idleSeconds,
                        stallThreshold.toSeconds());
                alert(ALERT_STALLED + today, "【埋点写库线程停滞】环境 " + appEnv + "\n"
                        + "写库线程已 " + idleSeconds + " 秒没有完成一轮（阈值 " + stallThreshold.toSeconds()
                        + " 秒），线程仍在，未重启。\n"
                        + "影响：内存队列持续积压，满 analytics.ingest.queue-max-bytes 后接收端返回 429。当天只告警一次。\n"
                        + "处理：多半卡在埋点库（锁等待 / 连接池耗尽 / 网络假死）：查 pg_stat_activity 中 analytics 池的会话；"
                        + "见 docs/runbooks/runbook-analytics-pipeline.md。");
                return Outcome.STALLED;
            }
            return Outcome.HEALTHY;
        } catch (RuntimeException e) {
            log.warn("analytics ingest flusher watchdog failed: {}", e.getClass().getSimpleName());
            return Outcome.SKIPPED;
        }
    }

    private void alert(String key, String text) {
        try {
            alerts.send(OpsChannel.TECH, key, text);
        } catch (RuntimeException e) {
            log.warn("analytics ingest flusher watchdog alert failed: {}", e.getClass().getSimpleName());
        }
    }
}

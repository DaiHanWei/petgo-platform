package com.tailtopia.analytics.config;

import com.tailtopia.analytics.erasure.ErasureJob;
import com.tailtopia.analytics.ingest.IngestCircuitBreaker;
import com.tailtopia.analytics.ingest.IngestFlusherWatchdog;
import com.tailtopia.analytics.ingest.IngestProperties;
import com.tailtopia.analytics.ingest.UnknownEventAlertJob;
import com.tailtopia.analytics.rollup.AnalyticsRollupProperties;
import com.tailtopia.analytics.rollup.Ga4DailySnapshotJob;
import com.tailtopia.analytics.rollup.Ga4SnapshotProperties;
import com.tailtopia.analytics.rollup.RollupJob;
import com.tailtopia.analytics.rollup.RollupRerunJob;
import com.tailtopia.analytics.store.DetailBuilder;
import com.tailtopia.analytics.store.DetailRecomputeJob;
import com.tailtopia.analytics.store.PartitionMaintenanceJob;
import com.tailtopia.analytics.store.StorageWatermarkJob;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 埋点定时任务的编程式注册（analytics-pipeline Story 4.9 AC9 / AC11）。
 *
 * <h2>为什么不用 {@code @Scheduled(cron = "${…}")}</h2>
 * 注解里的占位符在 {@code ScheduledAnnotationBeanPostProcessor} 里解析，cron 非法 / 时长写错会直接抛
 * {@code IllegalStateException}，<b>整个后端起不来</b>——违背架构「启动隔离：埋点出问题只停埋点，不伤业务」。
 * 这里先校验（{@code CronExpression.isValidExpression} / {@link ConfigValues#duration}），合法才注册：
 * <ul>
 *   <li><b>汇总</b>（{@link RollupJob} / {@link RollupRerunJob}）与 <b>GA4 快照</b>：cron 等配置由各自属性类校验，非法 = 属性类已停用该功能
 *       → <b>不注册</b>（story AC9）；告警由 {@link AnalyticsConfigAlert} / {@code Ga4DailySnapshotJob} 在就绪时发。</li>
 *   <li><b>存储水位 / 熔断 / 写库看门狗</b>：用属性类已校验（非法即回退默认）的值注册。</li>
 *   <li><b>其余</b>（分区维护、未登记事件、明细转换、明细重算、注销擦除）：这些是管道正常运转 / 合规承诺所需，非法值<b>回退默认调度</b>
 *       照常注册，登记到 {@link AnalyticsConfigProblems}，就绪时 TECH 告警。</li>
 * </ul>
 * {@code "-"}（{@code Scheduled.CRON_DISABLED}）仍表示「不注册」，测试基类靠它关闭各定时任务。
 * 键名与默认值与原注解完全一致；调度仍走全局调度池（{@code spring.task.scheduling}）。
 */
@Configuration(proxyBeanMethods = false)
public class AnalyticsSchedulingConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsSchedulingConfig.class);

    static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final Environment env;
    private final AnalyticsConfigProblems problems;
    private final ObjectProvider<AnalyticsRollupProperties> rollupProps;
    private final ObjectProvider<Ga4SnapshotProperties> ga4Props;
    private final ObjectProvider<AnalyticsStorageProperties> storageProps;
    private final ObjectProvider<IngestProperties> ingestProps;
    private final ObjectProvider<RollupJob> rollupJob;
    private final ObjectProvider<RollupRerunJob> rollupRerunJob;
    private final ObjectProvider<Ga4DailySnapshotJob> ga4Job;
    private final ObjectProvider<ErasureJob> erasureJob;
    private final ObjectProvider<UnknownEventAlertJob> unknownEventJob;
    private final ObjectProvider<StorageWatermarkJob> watermarkJob;
    private final ObjectProvider<PartitionMaintenanceJob> partitionJob;
    private final ObjectProvider<DetailBuilder> detailBuilder;
    private final ObjectProvider<DetailRecomputeJob> recomputeJob;
    private final ObjectProvider<IngestCircuitBreaker> breaker;
    private final ObjectProvider<IngestFlusherWatchdog> watchdog;

    /** 本次注册的结果（任务名 → 调度描述），供测试与启动日志。 */
    private final List<String> registered = new ArrayList<>();

    public AnalyticsSchedulingConfig(Environment env, AnalyticsConfigProblems problems,
            ObjectProvider<AnalyticsRollupProperties> rollupProps, ObjectProvider<Ga4SnapshotProperties> ga4Props,
            ObjectProvider<AnalyticsStorageProperties> storageProps, ObjectProvider<IngestProperties> ingestProps,
            ObjectProvider<RollupJob> rollupJob, ObjectProvider<RollupRerunJob> rollupRerunJob,
            ObjectProvider<Ga4DailySnapshotJob> ga4Job, ObjectProvider<ErasureJob> erasureJob,
            ObjectProvider<UnknownEventAlertJob> unknownEventJob, ObjectProvider<StorageWatermarkJob> watermarkJob,
            ObjectProvider<PartitionMaintenanceJob> partitionJob, ObjectProvider<DetailBuilder> detailBuilder,
            ObjectProvider<DetailRecomputeJob> recomputeJob, ObjectProvider<IngestCircuitBreaker> breaker,
            ObjectProvider<IngestFlusherWatchdog> watchdog) {
        this.env = env;
        this.problems = problems;
        this.rollupProps = rollupProps;
        this.ga4Props = ga4Props;
        this.storageProps = storageProps;
        this.ingestProps = ingestProps;
        this.rollupJob = rollupJob;
        this.rollupRerunJob = rollupRerunJob;
        this.ga4Job = ga4Job;
        this.erasureJob = erasureJob;
        this.unknownEventJob = unknownEventJob;
        this.watermarkJob = watermarkJob;
        this.partitionJob = partitionJob;
        this.detailBuilder = detailBuilder;
        this.recomputeJob = recomputeJob;
        this.breaker = breaker;
        this.watchdog = watchdog;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registered.clear();
        // 汇总（4.1）：配置非法 = 属性类已停用汇总 → 不注册（AC9）
        AnalyticsRollupProperties rollup = rollupProps.getIfAvailable();
        if (rollup != null && rollup.invalidReason() == null) {
            rollupJob.ifAvailable(j -> cron(registrar, "rollup", rollup.cron(), WIB, j::scheduled));
            rollupRerunJob.ifAvailable(j -> fixedDelay(registrar, "rollup-rerun", rollup.rerunFixedDelay(),
                    rollup.rerunInitialDelay(), j::scheduled));
        } else if (rollup != null) {
            log.warn("analytics rollup schedules not registered: invalid config");
        }
        // GA4 快照（4.2）：同上
        Ga4SnapshotProperties ga4 = ga4Props.getIfAvailable();
        if (ga4 != null && ga4.invalidReason() == null) {
            ga4Job.ifAvailable(j -> cron(registrar, "ga4-snapshot", ga4.cron(), WIB, j::scheduled));
        } else if (ga4 != null) {
            log.warn("analytics ga4 snapshot schedule not registered: invalid config");
        }
        // 存储水位 / 熔断（1.10）：属性类已校验（非法回退默认），熔断另由 valid() 停用按水位停收
        storageProps.ifAvailable(p -> {
            watermarkJob.ifAvailable(j -> cron(registrar, "storage-watermark", p.watermarkCron(), WIB, j::scheduled));
            breaker.ifAvailable(b -> fixedDelay(registrar, "ingest-breaker", Duration.ofMillis(p.breakerCheckMs()),
                    p.breakerInitialDelay(), b::scheduled));
        });
        // 写库线程看门狗（4.9）：首延 = 间隔
        ingestProps.ifAvailable(p -> watchdog.ifAvailable(w -> fixedDelay(registrar, "ingest-flusher-watchdog",
                p.watchdogInterval(), p.watchdogInterval(), w::scheduled)));
        // 其余：非法回退默认调度
        partitionJob.ifAvailable(j -> cron(registrar, "partition-maintenance",
                cronOrDefault("analytics.partition.cron", "0 5 0 * * *"), ZoneOffset.UTC, j::scheduled));
        unknownEventJob.ifAvailable(j -> cron(registrar, "unknown-event-alert",
                cronOrDefault("analytics.unknown-event-alert.cron", "0 30 1 * * *"), ZoneOffset.UTC, j::scheduled));
        erasureJob.ifAvailable(j -> cron(registrar, "erasure",
                cronOrDefault("analytics.erasure.cron", "0 0 2 * * *"), WIB, j::scheduled));
        detailBuilder.ifAvailable(j -> fixedDelay(registrar, "detail-builder",
                delayOrDefault("analytics.detail.fixed-delay-ms", false),
                delayOrDefault("analytics.detail.initial-delay-ms", true), j::scheduled));
        recomputeJob.ifAvailable(j -> fixedDelay(registrar, "detail-recompute",
                delayOrDefault("analytics.recompute.fixed-delay-ms", false),
                delayOrDefault("analytics.recompute.initial-delay-ms", true), j::scheduled));
        log.info("analytics schedules registered: {}", registered);
    }

    /** 本次注册结果（测试用）。 */
    List<String> registered() {
        return List.copyOf(registered);
    }

    /** 读 cron；非法 → 登记问题并回退默认。{@code "-"} 原样返回（= 不注册）。 */
    String cronOrDefault(String key, String def) {
        String raw = env.getProperty(key, def);
        if (ConfigValues.isValidCronOrDisabled(raw)) {
            return raw.strip();
        }
        problems.report(key + " 不是合法 cron（已按默认 " + def + " 调度）");
        log.warn("analytics schedule invalid, using default: key={}", key);
        return def;
    }

    /** 读毫秒时长（也接受 {@code 60s}）；非法 → 登记问题并回退默认 60000 毫秒（与原注解默认一致）。 */
    Duration delayOrDefault(String key, boolean allowZero) {
        Duration def = Duration.ofMillis(60_000);
        String raw = env.getProperty(key, "60000");
        Duration d = ConfigValues.duration(raw, ChronoUnit.MILLIS, allowZero);
        if (d != null) {
            return d;
        }
        problems.report(key + " 须为" + (allowZero ? "非负" : "正") + "的时长（已按默认 60000 毫秒调度）");
        log.warn("analytics schedule invalid, using default: key={}", key);
        return def;
    }

    private void cron(ScheduledTaskRegistrar registrar, String name, String expression, ZoneId zone, Runnable task) {
        if (ConfigValues.isCronDisabled(expression)) {
            registered.add(name + "=disabled");
            return;
        }
        registrar.addCronTask(new CronTask(task, new CronTrigger(expression, zone)));
        registered.add(name + "=cron(" + expression + " " + zone + ")");
    }

    private void fixedDelay(ScheduledTaskRegistrar registrar, String name, Duration interval, Duration initialDelay,
            Runnable task) {
        registrar.addFixedDelayTask(new FixedDelayTask(task, interval, initialDelay));
        registered.add(name + "=fixedDelay(" + interval + ", initial " + initialDelay + ")");
    }
}

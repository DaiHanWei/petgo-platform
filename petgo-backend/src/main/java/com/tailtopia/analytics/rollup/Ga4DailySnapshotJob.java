package com.tailtopia.analytics.rollup;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.admin.dailyreport.Ga4ActiveUsersClient;
import com.tailtopia.admin.dailyreport.Ga4ActiveUsersClient.Ga4VersionSplit;
import com.tailtopia.analytics.AnalyticsDauSourceProperties;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.store.EventTimeCorrector;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * GA4 每日快照（analytics-pipeline Story 4.2，架构 D-11）：每天 WIB 02:30（早于 03:00 的汇总），经
 * {@link Ga4ActiveUsersClient#activeUsersByVersion} 拉 GA4 全版本日活与 V1.5.0+ 日活，写入 {@code ga4_daily_snapshot}。
 *
 * <h2>拉哪些天</h2>
 * WIB 昨天往前 3 天（D−1、D−2、D−3，每次覆盖旧值：GA4 有处理延迟，02:30 拉到的 D−1 只是初值），<b>加上</b>最近
 * {@code backfill-days}（默认 14）天内 {@code ga4_daily_snapshot} 缺行的日期（「下次运行补拉」）。
 *
 * <h2>出网与事务</h2>
 * 逐日调 GA4，<b>不在埋点库事务里出网</b>；拿到结果后 {@link Ga4SnapshotRepository#upsert} 单独短事务写入
 * （事务内 {@code pg_try_advisory_xact_lock(NAMESPACE, GA4_SNAPSHOT)}，拿不到 = 别的实例在写，本轮停止）。
 *
 * <h2>跳过与失败</h2>
 * <ul>
 *   <li>开关关闭（stag：{@code ANALYTICS_GA4_SNAPSHOT_ENABLED=false}）、GA4 未配置、埋点管道停用 → 跳过，只记一条 INFO，不告警。</li>
 *   <li>某日 GA4 返回 null（拉取失败）或写库失败 → 不动旧行，在事务外 TECH 告警（去重键 {@code analytics-ga4:<日期>}），
 *       下一次运行补拉（缺行的日期在补拉窗口内会再拉）。</li>
 * </ul>
 * 日志只记日期、计数与异常类名，不打 token / 密钥 / 响应体。测试设 {@code analytics.ga4-snapshot.cron=-} 关闭，直接调 {@link #run()}。
 */
@Component
public class Ga4DailySnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(Ga4DailySnapshotJob.class);

    /** 拉取 / 写入失败告警去重键前缀，后接 WIB 日期（story 指定）。 */
    public static final String ALERT_PREFIX = "analytics-ga4:";

    /** 配置非法告警去重键（代码复审 2026-11-12：配置非法只停用本功能并告警，不阻断启动）。 */
    static final String CONFIG_ALERT_KEY = "analytics-ga4-config-invalid";

    /** 一次运行的结局。 */
    public enum Outcome {
        /** 开关关闭或埋点管道停用。 */
        SKIPPED_DISABLED,
        /** GA4 未配置（property-id / 密钥为空）。 */
        SKIPPED_NOT_CONFIGURED,
        /** 本 JVM 内已有一轮在跑。 */
        SKIPPED_BUSY,
        /** 没拿到 advisory lock（别的实例在写），本轮中途停止。 */
        SKIPPED_LOCKED,
        /** 全部日期成功。 */
        DONE,
        /** 至少一个日期失败（已告警）。 */
        FAILED
    }

    /** 一次运行的汇总：成功写入与失败的日期。 */
    public record RunResult(Outcome outcome, List<LocalDate> written, List<LocalDate> failed) {

        static RunResult of(Outcome outcome) {
            return new RunResult(outcome, List.of(), List.of());
        }
    }

    private final AnalyticsPipelineState state;
    private final Ga4ActiveUsersClient client;
    private final Ga4SnapshotRepository repository;
    private final Ga4SnapshotProperties props;
    private final ReconcileProperties reconcile;
    private final OpsAlertService alerts;
    private final AnalyticsDauSourceProperties dauSource;
    private final String appEnv;
    private final Clock clock;
    private final ReentrantLock runLock = new ReentrantLock();

    @Autowired
    public Ga4DailySnapshotJob(AnalyticsPipelineState state, Ga4ActiveUsersClient client,
            Ga4SnapshotRepository repository, Ga4SnapshotProperties props, ReconcileProperties reconcile,
            OpsAlertService alerts, AnalyticsDauSourceProperties dauSource,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(state, client, repository, props, reconcile, alerts, dauSource, appEnv, Clock.systemUTC());
    }

    /** 测试用：注入时钟、假 GA4 客户端、mock 告警。 */
    Ga4DailySnapshotJob(AnalyticsPipelineState state, Ga4ActiveUsersClient client, Ga4SnapshotRepository repository,
            Ga4SnapshotProperties props, ReconcileProperties reconcile, OpsAlertService alerts, String appEnv,
            Clock clock) {
        this(state, client, repository, props, reconcile, alerts, null, appEnv, clock);
    }

    private Ga4DailySnapshotJob(AnalyticsPipelineState state, Ga4ActiveUsersClient client,
            Ga4SnapshotRepository repository, Ga4SnapshotProperties props, ReconcileProperties reconcile,
            OpsAlertService alerts, AnalyticsDauSourceProperties dauSource, String appEnv, Clock clock) {
        this.dauSource = dauSource;
        this.state = state;
        this.client = client;
        this.repository = repository;
        this.props = props;
        this.reconcile = reconcile;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.clock = clock;
    }

    @Scheduled(cron = "${analytics.ga4-snapshot.cron:0 30 2 * * *}", zone = "Asia/Jakarta")
    public void scheduled() {
        run();
    }

    /**
     * 应用就绪时检查 GA4 快照 / 对账 / 日活来源配置：非法值在绑定时已停用对应功能（快照任务停跑；日活来源回退 GA4）并打 WARN，
     * 这里补发一次 TECH 告警。🔴 埋点配置写错不能让业务后端起不来（架构「故障隔离」），也不能静默。绝不抛。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void checkConfigOnStartup() {
        List<String> problems = configProblems();
        if (dauSource != null && !dauSource.valid()) {
            problems.add("analytics.dau-source 不是 GA4 / SELF（已回退 GA4）");
        }
        if (problems.isEmpty()) {
            return;
        }
        try {
            alerts.send(OpsChannel.TECH, CONFIG_ALERT_KEY, "【埋点配置非法】环境 " + appEnv + "\n"
                    + "问题：" + String.join("；", problems) + "\n"
                    + "影响：后端照常运行；GA4 日活快照停跑（日活对账显示 NO_GA4）/ 日活来源按 GA4。\n"
                    + "处理：改正 env 后重启（见 runbook-analytics-pipeline §5）。");
        } catch (RuntimeException e) {
            log.warn("analytics config alert failed: {}", e.getClass().getSimpleName());
        }
    }

    /** 快照任务自身的配置问题（只含键名，不含配置值）。 */
    private List<String> configProblems() {
        List<String> problems = new ArrayList<>();
        if (props.invalidReason() != null) {
            problems.add(props.invalidReason() + "（GA4 快照已停用）");
        }
        if (!reconcile.valid()) {
            problems.add("analytics.reconcile.min-app-version 格式非法（GA4 快照已停用）");
        }
        return problems;
    }

    /** 跑一轮。绝不抛。 */
    public RunResult run() {
        if (!configProblems().isEmpty()) {
            log.warn("analytics ga4 snapshot skipped: invalid config {}", configProblems());
            return RunResult.of(Outcome.SKIPPED_DISABLED);
        }
        if (!props.enabled() || !state.isEnabled()) {
            log.info("analytics ga4 snapshot skipped: enabled={} pipeline={}", props.enabled(),
                    state.reason().map(Enum::name).orElse("ENABLED"));
            return RunResult.of(Outcome.SKIPPED_DISABLED);
        }
        if (!client.isConfigured()) {
            log.info("analytics ga4 snapshot skipped: GA4 not configured (GA4_PROPERTY_ID / GA4_SA_KEY_B64 empty)");
            return RunResult.of(Outcome.SKIPPED_NOT_CONFIGURED);
        }
        if (!runLock.tryLock()) {
            log.info("analytics ga4 snapshot skipped: another run in this JVM");
            return RunResult.of(Outcome.SKIPPED_BUSY);
        }
        try {
            return doRun();
        } catch (RuntimeException e) {
            log.warn("analytics ga4 snapshot crashed: {}", RollupService.describe(e));
            return RunResult.of(Outcome.FAILED);
        } finally {
            runLock.unlock();
        }
    }

    private RunResult doRun() {
        long t0 = System.nanoTime();
        LocalDate yesterday = EventTimeCorrector.wibDate(clock.instant()).minusDays(1);
        Set<LocalDate> existing;
        try {
            existing = repository.existingDates(yesterday.minusDays(props.backfillDays() - 1L), yesterday);
        } catch (RuntimeException e) {
            // 读不到已有日期只影响「补拉」，固定回拉的 3 天照拉
            log.warn("analytics ga4 snapshot existing dates read failed: {}", RollupService.describe(e));
            existing = null;
        }
        List<LocalDate> dates = datesToFetch(yesterday, props.backfillDays(), existing);
        String minVersion = reconcile.minAppVersion();
        List<LocalDate> written = new ArrayList<>();
        List<LocalDate> failed = new ArrayList<>();
        for (LocalDate day : dates) {
            // 出网：不在任何事务里
            Ga4VersionSplit split = client.activeUsersByVersion(day, minVersion);
            if (split == null) {
                failed.add(day);
                log.warn("analytics ga4 snapshot fetch failed: date={}", day);
                alert(day, "GA4 拉取失败（未配置之外的任何失败：换 token、HTTP 非 200、超时、响应结构不符）");
                continue;
            }
            boolean ok;
            try {
                ok = repository.upsert(day, split.all(), split.newVersion(), minVersion, clock.instant());
            } catch (RuntimeException e) {
                // 短事务已回滚：在事务外告警
                failed.add(day);
                log.warn("analytics ga4 snapshot write failed: date={} cause={}", day, RollupService.describe(e));
                alert(day, "写入 ga4_daily_snapshot 失败（" + RollupService.describe(e) + "）");
                continue;
            }
            if (!ok) {
                log.info("analytics ga4 snapshot stopped: advisory lock held elsewhere (date={})", day);
                return new RunResult(Outcome.SKIPPED_LOCKED, List.copyOf(written), List.copyOf(failed));
            }
            written.add(day);
        }
        Outcome outcome = failed.isEmpty() ? Outcome.DONE : Outcome.FAILED;
        log.info("analytics ga4 snapshot finished: outcome={} dates={} written={} failed={} ms={}", outcome,
                dates.size(), written.size(), failed, (System.nanoTime() - t0) / 1_000_000);
        return new RunResult(outcome, List.copyOf(written), List.copyOf(failed));
    }

    /**
     * 要拉的日期（升序）：{@code yesterday} 往前 {@link Ga4SnapshotProperties#REFETCH_DAYS} 天（每次覆盖），加上
     * {@code [yesterday − backfillDays + 1, yesterday]} 内不在 {@code existing} 里的日期。
     *
     * @param existing 补拉窗口内已有快照的日期；null = 读取失败，只拉固定的 3 天
     */
    static List<LocalDate> datesToFetch(LocalDate yesterday, int backfillDays, Set<LocalDate> existing) {
        TreeSet<LocalDate> out = new TreeSet<>();
        for (int i = 0; i < Ga4SnapshotProperties.REFETCH_DAYS; i++) {
            out.add(yesterday.minusDays(i));
        }
        if (existing != null) {
            for (int i = 0; i < backfillDays; i++) {
                LocalDate d = yesterday.minusDays(i);
                if (!existing.contains(d)) {
                    out.add(d);
                }
            }
        }
        return List.copyOf(out);
    }

    private void alert(LocalDate day, String cause) {
        try {
            alerts.send(OpsChannel.TECH, ALERT_PREFIX + day, "【GA4 日活快照失败】环境 " + appEnv + "\n"
                    + "日期：" + day + "（WIB）\n原因：" + cause + "\n"
                    + "影响：该日 ga4_daily_snapshot 保留旧值（没有则缺行），日活对账该日显示 NO_GA4；日报不受影响。\n"
                    + "处理：查后端日志「ga4 runReport」的状态码 / 异常类名；下一次运行（每天 WIB 02:30）自动补拉最近 "
                    + props.backfillDays() + " 天内缺的日期。");
        } catch (RuntimeException e) {
            log.warn("analytics ga4 snapshot alert failed: {}", e.getClass().getSimpleName());
        }
    }
}

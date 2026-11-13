package com.tailtopia.analytics.store;

import static com.tailtopia.analytics.store.RawPartitionPlanner.DEFAULT_PARTITION;
import static com.tailtopia.analytics.store.RawPartitionPlanner.PARENT;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsLockKeys;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 埋点库分区维护（analytics-pipeline Story 1.3，D-8 / FR-12）。
 *
 * <p>原始层预建（Story 1.3，{@link #ensureRawPartitions}）：保证 UTC 今天起 8 天的 {@code events_raw_pYYYYMMDD} 都存在。
 * 明细层预建（Story 1.6，{@link #ensureDetailPartitions}）：保证 <b>WIB</b> 今天起 8 天的 {@code events_detail_pYYYYMMDD}
 * 都存在（明细层没有 DEFAULT 兜底，缺分区时 {@code DetailBuilder} 整片回滚、不推进进度）。
 * 过期删除（Story 1.7，{@link #runRetention}）：明细层删超过 3 个月的分区；原始层删超过 35 天、且明细进度游标已越过那一天的分区，
 * 被游标挡住的不删并告警。判定在纯函数 {@link RetentionPolicy}。各自一个方法，由 {@link #run()} 串起来，不要把逻辑写进
 * {@code @Scheduled} 方法。
 *
 * <p>删除时<b>每个分区单独一个埋点事务</b>（拿同一把维护锁 + {@code SET LOCAL lock_timeout = '5s'}）：{@code DROP} 分区要父表的
 * ACCESS EXCLUSIVE 锁，拿不到（AI 长查询、接收写入在途）就超时失败、告警、下次调度重试，绝不排在锁队列里挡住接收写入；
 * 一个分区失败不影响其他分区。存在 {@code RUNNING} 的重算请求时，原始层本轮不删（重算正在读它）。
 *
 * <p>原始层、明细层各一个埋点事务，拿同一把维护锁；一层失败不影响另一层照常预建。
 *
 * <h2>触发</h2>
 * <ul>
 *   <li>{@code @Scheduled}：默认每天 UTC 00:05（{@code analytics.partition.cron}；测试设 {@code -} 关闭，直接调 {@link #run()}）。</li>
 *   <li>启动后立即一次（{@link ApplicationReadyEvent}，此时 {@code AnalyticsBootstrap} 的埋点迁移已跑完），
 *       保证新环境 / 久未部署的环境开张就有分区。</li>
 * </ul>
 *
 * <h2>一次运行 = 一个埋点库事务</h2>
 * {@code pg_try_advisory_xact_lock(NAMESPACE, PARTITION_MAINTENANCE)} 拿不到 → 别的实例 / 线程在跑，直接返回。
 * 某天的分区不存在时：DEFAULT 里没有该区间的行 → 普通 {@code CREATE TABLE ... PARTITION OF}；
 * 有 → DETACH DEFAULT → 建分区 → 搬行 → ATTACH DEFAULT（PG 不允许在 DEFAULT 已有该区间行时直接建分区）。
 *
 * <h2>🔴 失败只告警</h2>
 * 管道停用（库不可用 / 迁移失败）时跳过；清单无效（{@link Reason#CATALOG_INVALID}）时库是好的，照常建分区。
 * 运行异常 → WARN（只打异常类名）+ TECH 告警，<b>不抛、不改管道状态</b>：接收照常写，没分区的行落 DEFAULT 兜底，
 * 下次调度自动重试，7 天预建给足修复时间。DEFAULT 非空 → TECH 告警。告警一律在埋点事务返回后发
 * （{@link OpsAlertService} 的 afterCommit 挂在业务库事务同步上，不在埋点事务里调用它）。
 *
 * <p>埋点库只经 {@code @Qualifier("analytics")} 的 {@link JdbcTemplate} / {@link TransactionTemplate} 访问，禁止 JPA。
 * 日志只打日期、分区名与行数。
 */
@Component
public class PartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);

    static final String ALERT_DEFAULT_NONEMPTY = "analytics-raw-default-nonempty:";
    static final String ALERT_FAILED = "analytics-partition-failed:";
    static final String ALERT_DETAIL_FAILED = "analytics-detail-partition-failed:";
    /** Story 1.7：原始层分区年龄已过、但明细进度没越过（按 WIB 日去重，story 指定键名）。 */
    static final String ALERT_RAW_RETENTION_BLOCKED = "analytics:raw-retention-blocked:";
    /** Story 1.7：过期分区删除失败（含拿不到表锁超时），按 UTC 日去重。 */
    static final String ALERT_RETENTION_FAILED = "analytics-retention-failed:";

    /** 删分区 DDL 等表锁的上限：拿不到就失败、告警、下次重试，不排队挡接收写入。 */
    static final String DROP_LOCK_TIMEOUT = "5s";

    static final String RAW_BUILT_SQL = "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid "
            + "WHERE i.inhparent = '" + PARENT + "'::regclass";

    /** 一次运行的结局。 */
    public enum Outcome {
        /** 管道停用（库不可用 / 迁移失败），未运行。 */
        SKIPPED_DISABLED,
        /** 没拿到 advisory lock（别的实例 / 线程在跑），未运行。 */
        SKIPPED_LOCKED,
        /** 运行完成。 */
        DONE,
        /** 运行异常（已告警，未向外抛）。 */
        FAILED
    }

    /**
     * 原始层预建结果。
     *
     * @param lockAcquired     是否拿到锁
     * @param created          本次新建的分区（含从 DEFAULT 搬行的）
     * @param movedFromDefault 本次走了 DETACH / 搬行 / ATTACH 路径的分区
     * @param movedRows        从 DEFAULT 搬走的行数
     * @param defaultRows      运行结束时 DEFAULT 里剩下的行数
     */
    public record RawResult(boolean lockAcquired, List<String> created, List<String> movedFromDefault,
            long movedRows, long defaultRows) {

        static RawResult notLocked() {
            return new RawResult(false, List.of(), List.of(), 0, 0);
        }
    }

    /**
     * 明细层预建结果（Story 1.6）。
     *
     * @param lockAcquired 是否拿到锁
     * @param created      本次新建的分区
     */
    public record DetailResult(boolean lockAcquired, List<String> created) {

        static DetailResult notLocked() {
            return new DetailResult(false, List.of());
        }
    }

    /**
     * 过期删除结果（Story 1.7）。
     *
     * @param detailDropped 本次删掉的明细层分区
     * @param rawDropped    本次删掉的原始层分区
     * @param rawBlocked    年龄已过但被明细进度挡住、未删的原始层分区日（UTC）
     * @param rawDeferred   有进行中的重算、本轮未删的原始层分区日（UTC）
     * @param lockSkipped   没拿到维护锁（别的实例在跑）而跳过的分区
     * @param failed        删除失败的分区（已告警，下次重试）
     */
    public record RetentionResult(List<String> detailDropped, List<String> rawDropped, List<LocalDate> rawBlocked,
            List<LocalDate> rawDeferred, List<String> lockSkipped, List<String> failed) {
    }

    /**
     * 一次运行的汇总。{@code raw} / {@code detail} / {@code retention} 为对应步骤成功运行的结果，失败或未运行时为空；
     * 任一步失败（含个别分区删除失败）→ {@link Outcome#FAILED}（已告警）。
     */
    public record RunResult(Outcome outcome, RawResult raw, DetailResult detail, RetentionResult retention) {

        RunResult(Outcome outcome, RawResult raw) {
            this(outcome, raw, null, null);
        }

        RunResult(Outcome outcome, RawResult raw, DetailResult detail) {
            this(outcome, raw, detail, null);
        }
    }

    private final AnalyticsPipelineState state;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final Clock clock;

    @Autowired
    public PartitionMaintenanceJob(AnalyticsPipelineState state,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) TransactionTemplate analyticsTransactionTemplate,
            OpsAlertService alerts,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(state, analyticsJdbcTemplate, analyticsTransactionTemplate, alerts, appEnv, Clock.systemUTC());
    }

    /** 测试用：注入固定时钟。 */
    PartitionMaintenanceJob(AnalyticsPipelineState state, JdbcTemplate jdbc, TransactionTemplate tx,
            OpsAlertService alerts, String appEnv, Clock clock) {
        this.state = state;
        this.jdbc = jdbc;
        this.tx = tx;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.clock = clock;
    }

    /** 每天 UTC 00:05。 */
    // Story 4.9 AC11：调度由 AnalyticsSchedulingConfig 编程式注册（键名 / 默认值不变，非法值不再让启动失败）
    public void scheduled() {
        run();
    }

    /** 启动后立即跑一次（埋点迁移已在 bean 初始化阶段完成）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        run();
    }

    /** 跑一轮维护：条件不满足跳过，失败告警，绝不抛。 */
    public RunResult run() {
        if (!shouldRun()) {
            log.info("analytics partition maintenance skipped: pipeline disabled ({})", state.reason().orElse(null));
            return new RunResult(Outcome.SKIPPED_DISABLED, null);
        }
        LocalDate today = RawPartitionPlanner.utcDay(clock.instant());
        RawResult raw;
        try {
            raw = tx.execute(status -> ensureRawPartitions(today));
        } catch (RuntimeException | Error e) {
            String cause = describe(e);
            log.warn("analytics partition maintenance failed: {}", cause);
            alert(ALERT_FAILED + today, "【埋点分区维护失败】环境 " + appEnv + "\n"
                    + "原因：" + cause + "\n"
                    + "影响：埋点接收不受影响；缺分区的日期写进 events_raw_pdefault 兜底。"
                    + "每天 UTC 00:05 自动重试，已预建 " + RawPartitionPlanner.DAYS_AHEAD + " 天，有修复时间。\n"
                    + "处理：检查埋点库与写入账号权限（建表 / ALTER events_raw）；修好后重建后端容器会立即补跑一次。");
            DetailResult detail = runDetail();
            RetentionResult retention = runRetention();
            return new RunResult(Outcome.FAILED, null, detail, retention);
        }
        if (raw == null || !raw.lockAcquired()) {
            log.info("analytics partition maintenance skipped: lock held elsewhere");
            return new RunResult(Outcome.SKIPPED_LOCKED, null);
        }
        if (!raw.created().isEmpty() || raw.movedRows() > 0) {
            log.info("analytics raw partitions ensured: created={} movedFromDefault={} movedRows={}",
                    raw.created(), raw.movedFromDefault(), raw.movedRows());
        }
        if (raw.defaultRows() > 0) {
            log.warn("analytics raw default partition non-empty: rows={}", raw.defaultRows());
            alert(ALERT_DEFAULT_NONEMPTY + today, "【埋点原始层 DEFAULT 分区非空】环境 " + appEnv + "\n"
                    + "行数：" + raw.defaultRows() + "（" + DEFAULT_PARTITION + "）\n"
                    + "含义：有事件的收到时间（UTC）不在任何已建日分区内 —— 通常是分区维护连续失败超过 "
                    + RawPartitionPlanner.DAYS_AHEAD + " 天，或服务器时钟异常。\n"
                    + "影响：数据没丢，仍在原始层；落在今天起 8 天内的行下次维护会自动搬进对应分区。\n"
                    + "处理：核对服务器时钟与分区维护日志；超出预建范围的行需人工按 received_at 建分区后搬移。");
        }
        DetailResult detail = runDetail();
        RetentionResult retention = runRetention();
        boolean ok = detail != null && retention != null && retention.failed().isEmpty();
        return new RunResult(ok ? Outcome.DONE : Outcome.FAILED, raw, detail, retention);
    }

    /**
     * 明细层预建（独立的埋点事务）：失败只告警、返回 {@code null}；没拿到锁返回 {@link DetailResult#notLocked()}。
     */
    private DetailResult runDetail() {
        LocalDate todayWib = EventTimeCorrector.wibDate(clock.instant());
        DetailResult detail;
        try {
            detail = tx.execute(status -> ensureDetailPartitions(todayWib));
        } catch (RuntimeException | Error e) {
            String cause = describe(e);
            log.warn("analytics detail partition maintenance failed: {}", cause);
            alert(ALERT_DETAIL_FAILED + todayWib, "【埋点明细分区预建失败】环境 " + appEnv + "\n"
                    + "原因：" + cause + "\n"
                    + "影响：埋点接收与原始层不受影响；明细层没有 DEFAULT 兜底，若预建的 "
                    + DetailPartitions.DAYS_AHEAD + " 天用完仍未修好，明细转换会停在缺分区那天（数据仍在原始层，修好后自动追上）。"
                    + "每天 UTC 00:05 自动重试。\n"
                    + "处理：检查埋点库与写入账号权限（建表 / ALTER events_detail）；修好后重建后端容器会立即补跑一次。");
            return null;
        }
        if (detail == null) {
            return null;
        }
        if (!detail.lockAcquired()) {
            log.info("analytics detail partition maintenance skipped: lock held elsewhere");
        } else if (!detail.created().isEmpty()) {
            log.info("analytics detail partitions ensured: created={}", detail.created());
        }
        return detail;
    }

    /** 库可用即跑；清单无效时库是好的，照常建分区（免得清单一错连分区也断）。 */
    private boolean shouldRun() {
        return state.isEnabled() || state.reason().filter(r -> r == Reason.CATALOG_INVALID).isPresent();
    }

    /**
     * 原始层预建：在调用方的埋点事务内执行（先拿事务级 advisory lock）。
     *
     * @param todayUtc UTC 今天
     */
    RawResult ensureRawPartitions(LocalDate todayUtc) {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class,
                AnalyticsLockKeys.NAMESPACE, AnalyticsLockKeys.PARTITION_MAINTENANCE);
        if (!Boolean.TRUE.equals(locked)) {
            return RawResult.notLocked();
        }
        List<String> created = new ArrayList<>();
        List<String> moved = new ArrayList<>();
        long movedRows = 0;
        for (LocalDate day : RawPartitionPlanner.daysToEnsure(todayUtc)) {
            String name = RawPartitionPlanner.partitionName(day);
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?::text) IS NOT NULL", Boolean.class,
                    name))) {
                continue;
            }
            String lower = RawPartitionPlanner.lowerBound(day);
            String upper = RawPartitionPlanner.upperBound(day);
            String inRange = "received_at >= " + lower + "::timestamptz AND received_at < " + upper + "::timestamptz";
            String create = "CREATE TABLE IF NOT EXISTS " + name + " PARTITION OF " + PARENT
                    + " FOR VALUES FROM (" + lower + ") TO (" + upper + ")";
            boolean defaultHasRows = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM " + DEFAULT_PARTITION + " WHERE " + inRange + ")", Boolean.class));
            if (!defaultHasRows) {
                jdbc.execute(create);
            } else {
                // PG 不允许 DEFAULT 已有该区间行时建分区：DETACH → 建 → 搬 → ATTACH，同一事务（AC3）。
                jdbc.execute("ALTER TABLE " + PARENT + " DETACH PARTITION " + DEFAULT_PARTITION);
                jdbc.execute(create);
                movedRows += jdbc.update("INSERT INTO " + PARENT + " SELECT * FROM " + DEFAULT_PARTITION
                        + " WHERE " + inRange);
                jdbc.update("DELETE FROM " + DEFAULT_PARTITION + " WHERE " + inRange);
                jdbc.execute("ALTER TABLE " + PARENT + " ATTACH PARTITION " + DEFAULT_PARTITION + " DEFAULT");
                moved.add(name);
            }
            created.add(name);
        }
        long defaultRows = 0;
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + DEFAULT_PARTITION + ")", Boolean.class))) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM " + DEFAULT_PARTITION, Long.class);
            defaultRows = n == null ? 0 : n;
        }
        return new RawResult(true, List.copyOf(created), List.copyOf(moved), movedRows, defaultRows);
    }

    /**
     * 明细层预建：在调用方的埋点事务内执行（先拿同一把维护锁）。没有 DEFAULT 分区，缺哪天直接建。
     *
     * @param todayWib WIB 今天（{@link EventTimeCorrector#wibDate}）
     */
    DetailResult ensureDetailPartitions(LocalDate todayWib) {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class,
                AnalyticsLockKeys.NAMESPACE, AnalyticsLockKeys.PARTITION_MAINTENANCE);
        if (!Boolean.TRUE.equals(locked)) {
            return DetailResult.notLocked();
        }
        List<String> created = new ArrayList<>();
        for (LocalDate day : DetailPartitions.daysToEnsure(todayWib)) {
            if (DetailPartitions.ensure(jdbc, day)) {
                created.add(DetailPartitions.partitionName(day));
            }
        }
        return new DetailResult(true, List.copyOf(created));
    }

    /**
     * 过期删除（Story 1.7）：先明细层、后原始层，每个分区一个埋点事务。失败只告警、绝不抛；列举阶段就失败返回 {@code null}。
     */
    RetentionResult runRetention() {
        Instant now = clock.instant();
        LocalDate todayUtc = RawPartitionPlanner.utcDay(now);
        LocalDate todayWib = EventTimeCorrector.wibDate(now);
        List<String> detailDropped = new ArrayList<>();
        List<String> rawDropped = new ArrayList<>();
        List<String> lockSkipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> failCauses = new ArrayList<>();
        RetentionPolicy.RawDecision decision;
        List<LocalDate> rawDeferred = List.of();
        Instant cursor;
        try {
            for (LocalDate d : RetentionPolicy.detailPartitionsToDrop(DetailPartitions.builtDates(jdbc), todayWib)) {
                dropPartition(DetailPartitions.partitionName(d), detailDropped, lockSkipped, failed, failCauses);
            }
            cursor = detailCursor();
            decision = RetentionPolicy.rawPartitionsToDrop(rawBuiltDates(jdbc), todayUtc, cursor);
            if (!decision.drop().isEmpty() && recomputeRunning()) {
                // 重算正在按收到时间读原始层：本轮不删，明天再删（数据不丢，只是晚一天释放磁盘）
                rawDeferred = decision.drop();
                log.info("analytics raw retention deferred: detail recompute running, partitions={}", rawDeferred.size());
            } else {
                for (LocalDate d : decision.drop()) {
                    dropPartition(RawPartitionPlanner.partitionName(d), rawDropped, lockSkipped, failed, failCauses);
                }
            }
        } catch (RuntimeException | Error e) {
            String cause = describe(e);
            log.warn("analytics partition retention failed: {}", cause);
            alert(ALERT_RETENTION_FAILED + todayUtc, retentionFailedText(cause));
            return null;
        }
        if (!detailDropped.isEmpty() || !rawDropped.isEmpty()) {
            log.info("analytics partitions dropped: detail={} raw={}", detailDropped, rawDropped);
        }
        if (!decision.blocked().isEmpty()) {
            log.warn("analytics raw retention blocked by detail progress: partitions={}", decision.blocked());
            alert(ALERT_RAW_RETENTION_BLOCKED + todayWib, "【埋点原始层过期分区未删：明细进度落后】环境 " + appEnv + "\n"
                    + "被挡住的分区（UTC 日）：" + decision.blocked() + "\n"
                    + "明细进度游标（pipeline_progress.detail）：" + (cursor == null ? "缺失" : cursor.toString()) + "\n"
                    + "含义：这些分区已超过 " + RetentionPolicy.RAW_RETENTION_DAYS + " 天，但那一天的原始行还没全部转进明细层，"
                    + "删了就永久丢数，所以保留。\n"
                    + "处理：查 DetailBuilder 的失败告警 / 日志（analytics detail build failed），修好后进度追上，下次维护自动删除。");
        }
        if (!failed.isEmpty()) {
            log.warn("analytics partition drop failed: partitions={} causes={}", failed, failCauses);
            alert(ALERT_RETENTION_FAILED + todayUtc, retentionFailedText(
                    "删除失败的分区 " + failed + "；原因 " + failCauses));
        }
        return new RetentionResult(List.copyOf(detailDropped), List.copyOf(rawDropped), decision.blocked(),
                rawDeferred, List.copyOf(lockSkipped), List.copyOf(failed));
    }

    private String retentionFailedText(String cause) {
        return "【埋点过期分区删除失败】环境 " + appEnv + "\n"
                + "原因：" + cause + "\n"
                + "影响：埋点接收与明细转换不受影响，只是过期数据晚删、多占磁盘。常见原因是 " + DROP_LOCK_TIMEOUT
                + " 内拿不到表锁（AI 长查询 / 写入在途），每天 UTC 00:05 自动重试。\n"
                + "处理：连续多天出现时，检查埋点库长事务（pg_stat_activity）与写入账号权限。";
    }

    /** 删一个分区：独立埋点事务，先拿维护锁，再设 lock_timeout，最后 DROP。失败记入 {@code failed}，不抛。 */
    private void dropPartition(String name, List<String> dropped, List<String> lockSkipped, List<String> failed,
            List<String> failCauses) {
        try {
            Boolean done = tx.execute(status -> {
                Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class,
                        AnalyticsLockKeys.NAMESPACE, AnalyticsLockKeys.PARTITION_MAINTENANCE);
                if (!Boolean.TRUE.equals(locked)) {
                    return Boolean.FALSE;
                }
                jdbc.execute("SET LOCAL lock_timeout = '" + DROP_LOCK_TIMEOUT + "'");
                jdbc.execute("DROP TABLE IF EXISTS " + name);
                return Boolean.TRUE;
            });
            if (Boolean.TRUE.equals(done)) {
                dropped.add(name);
            } else {
                lockSkipped.add(name);
            }
        } catch (RuntimeException e) {
            failed.add(name);
            failCauses.add(describe(e));
        }
    }

    private Instant detailCursor() {
        return jdbc.query(DetailBuilder.PROGRESS_SQL, rs -> {
            if (!rs.next()) {
                return null;
            }
            OffsetDateTime t = rs.getObject(1, OffsetDateTime.class);
            return t == null ? null : t.toInstant();
        }, DetailBuilder.PIPELINE);
    }

    private boolean recomputeRunning() {
        return Boolean.TRUE.equals(jdbc.queryForObject(DetailBuilder.RECOMPUTE_RUNNING_SQL, Boolean.class));
    }

    /** 当前挂在 {@code events_raw} 上的日分区（UTC 日期，不含 DEFAULT）。重算的保留期校验也用它。 */
    static Set<LocalDate> rawBuiltDates(JdbcTemplate jdbc) {
        return RetentionPolicy.rawPartitionDates(jdbc.queryForList(RAW_BUILT_SQL, String.class));
    }

    private void alert(String dedupeKey, String text) {
        try {
            alerts.send(OpsChannel.TECH, dedupeKey, text);
        } catch (RuntimeException e) {
            log.warn("analytics partition alert failed: {}", e.getClass().getSimpleName());
        }
    }

    /** 异常类名链（不含 message：可能带连接串 / 库名 / SQL）。 */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        for (int depth = 0; t != null && depth < 5; depth++) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return sb.toString();
    }
}

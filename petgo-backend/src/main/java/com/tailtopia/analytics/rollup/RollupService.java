package com.tailtopia.analytics.rollup;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsLockKeys;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.rollup.RollupProgressRepository.Progress;
import com.tailtopia.analytics.store.RetentionPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 汇总执行器（analytics-pipeline Story 4.1，架构 D-5 / D-6 / A8「汇总」「告警」）。
 *
 * <h2>单元 = （指标族, WIB 日）一个埋点库短事务</h2>
 * <ol>
 *   <li>{@code pg_try_advisory_xact_lock(NAMESPACE, ROLLUP)}：拿不到 = 别的实例在跑，本轮停止（INFO，不告警）。</li>
 *   <li>{@code SET LOCAL statement_timeout}（{@code analytics.rollup.statement-timeout}，默认 600s；写入账号默认 120s）。</li>
 *   <li>建 {@code tmp_excluded_users}（{@link RollupContext}）→ {@link RollupFamily#computeDay} → 推进 {@code agg_progress}，
 *       <b>同一事务提交</b>（AC3）。</li>
 * </ol>
 * 串行执行、不开并发（埋点池只有 3 条）；事务内不查业务库、不发网络请求（排除用户在运行开始时已读好）。
 *
 * <h2>定时续算（{@link #runScheduled}）</h2>
 * 每个指标族：起点 = {@code min(last_done_date + 1, asOf − recomputeDays + 1)}；从未成功过只算最近 {@code recomputeDays} 天
 * （更早的历史用手动重跑回补）；起点早于 {@code asOf − retention-days} 时截到下限并 TECH 告警。
 *
 * <h2>失败</h2>
 * 某指标族某日失败 → 该指标族本轮停止（进度不推进），写 {@code agg_progress.last_error}（只放异常类名链），
 * <b>事务回滚之后</b>在事务外发 TECH 告警（{@link OpsAlertService#send} 在活动事务里只挂 afterCommit，回滚即丢）；
 * 去重键 {@code analytics-rollup:<family>:<date>}。其他指标族照常；{@link RollupFamily#dependsOn} 了失败 / 跳过指标族的本轮跳过。
 * 下一次定时运行从 {@code last_done_date + 1} 自动补上。
 *
 * <h2>并发</h2>
 * JVM 内 {@link ReentrantLock#tryLock()} 防重入（定时运行与手动重跑共用）：拿不到直接返回 {@link Outcome#SKIPPED_BUSY}。
 *
 * <p>日志只打指标族、日期、耗时与计数，不打事件内容与用户标识。
 */
@Service
public class RollupService {

    private static final Logger log = LoggerFactory.getLogger(RollupService.class);

    /** 单元失败告警去重键前缀，后接 {@code <family>:<date>}（story 指定）。 */
    public static final String ALERT_FAILED = "analytics-rollup:";
    /** 进度落后超过保留期告警去重键前缀，后接 {@code <family>:<asOf>}。 */
    static final String ALERT_LAG = "analytics-rollup-lag:";
    /** 运行开始时读排除用户 / 进度失败的告警去重键前缀，后接 asOf。 */
    static final String ALERT_PREPARE = "analytics-rollup-prepare:";
    static final String LOCK_SQL = "SELECT pg_try_advisory_xact_lock(?, ?)";

    /** 一次运行的结局。 */
    public enum Outcome {
        /** 汇总关闭或埋点管道停用，未运行。 */
        SKIPPED_DISABLED,
        /** 本 JVM 内已有汇总 / 重跑在跑，未运行。 */
        SKIPPED_BUSY,
        /** 没拿到 advisory lock（别的实例在跑），本轮中途停止。 */
        SKIPPED_LOCKED,
        /** 全部指标族成功。 */
        DONE,
        /** 至少一个指标族失败或被跳过（已告警）。 */
        FAILED
    }

    /**
     * 一次运行的汇总。
     *
     * @param units           成功提交的单元数
     * @param failedFamilies  本轮失败的指标族
     * @param skippedFamilies 因依赖失败而跳过的指标族
     */
    public record RunResult(Outcome outcome, int units, List<String> failedFamilies, List<String> skippedFamilies) {

        static RunResult of(Outcome outcome) {
            return new RunResult(outcome, 0, List.of(), List.of());
        }
    }

    /**
     * 一个指标族本轮要算的区间 {@code [from, to]}（WIB）。
     *
     * @param progressBase 开算前的 {@code last_done_date}（为空 = 从未成功）：某日只有与它相连（{@code ≤ base + 1}）时才推进进度，
     *                     防止手动重跑一段较新的日期把进度跳过中间没算的缺口（代码复审 4-1 R2）
     */
    record Range(LocalDate from, LocalDate to, LocalDate progressBase) {
    }

    private enum UnitStatus {
        OK,
        LOCKED,
        FAILED
    }

    /** 没拿到锁：本轮停止。 */
    private static final class LockedException extends RuntimeException {

        LockedException() {
            super(null, null, false, false);
        }
    }

    private final AnalyticsPipelineState state;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcTemplate businessJdbc;
    private final RollupProgressRepository progress;
    private final List<RollupFamily> families;
    private final AnalyticsRollupProperties props;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final Clock clock;
    private final ReentrantLock runLock = new ReentrantLock();

    @Autowired
    public RollupService(AnalyticsPipelineState state,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) TransactionTemplate analyticsTransactionTemplate,
            JdbcTemplate businessJdbcTemplate,
            RollupProgressRepository progress,
            List<RollupFamily> families,
            AnalyticsRollupProperties props,
            OpsAlertService alerts,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(state, analyticsJdbcTemplate, analyticsTransactionTemplate, businessJdbcTemplate, progress, families, props,
                alerts, appEnv, Clock.systemUTC());
    }

    /** 测试用：注入时钟、mock 告警、自定义指标族。 */
    RollupService(AnalyticsPipelineState state, JdbcTemplate jdbc, TransactionTemplate tx, JdbcTemplate businessJdbc,
            RollupProgressRepository progress, List<RollupFamily> families, AnalyticsRollupProperties props,
            OpsAlertService alerts, String appEnv, Clock clock) {
        this.state = state;
        this.jdbc = jdbc;
        this.tx = tx;
        this.businessJdbc = businessJdbc;
        this.progress = progress;
        this.families = orderByDependencies(families);
        this.props = props;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.clock = clock;
    }

    /** 已注册指标族名（依赖序）。 */
    public List<String> familyNames() {
        return families.stream().map(RollupFamily::name).toList();
    }

    /** 本 JVM 内是否有汇总 / 重跑正在跑。 */
    public boolean isRunning() {
        return runLock.isLocked();
    }

    public AnalyticsRollupProperties properties() {
        return props;
    }

    /** 汇总开着且埋点管道可用。 */
    public boolean isEnabled() {
        return props.enabled() && state.isEnabled();
    }

    // ---------------------------------------------------------------- 入口

    /** 定时续算：每个指标族按 {@code agg_progress} 续算到 {@code asOf}，每次至少重算最近 {@code recomputeDays} 天。绝不抛。 */
    public RunResult runScheduled(LocalDate asOf) {
        return guarded("scheduled", () -> {
            Instant runAt = clock.instant();
            RollupContext ctx = prepare(asOf, runAt);
            if (ctx == null) {
                return RunResult.of(Outcome.FAILED);
            }
            Map<String, Progress> prog;
            try {
                prog = progress.findAll();
            } catch (RuntimeException e) {
                prepareFailed(asOf, "读 agg_progress", e);
                return RunResult.of(Outcome.FAILED);
            }
            LocalDate floor = floor(asOf, props.retentionDays());
            Map<String, Range> plan = new LinkedHashMap<>();
            for (RollupFamily f : families) {
                Progress p = prog.get(f.name());
                LocalDate start = startDate(p == null ? null : p.lastDoneDate(), asOf, f.recomputeDays());
                if (start.isBefore(floor)) {
                    long skipped = ChronoUnit.DAYS.between(start, floor);
                    log.warn("analytics rollup lag beyond retention: family={} from={} floor={} skippedDays={}",
                            f.name(), start, floor, skipped);
                    alert(ALERT_LAG + f.name() + ":" + asOf, "【埋点汇总进度落后超过明细保留期】环境 " + appEnv + "\n"
                            + "指标族：" + f.name() + "\n"
                            + "汇总进度落后超过明细保留期（" + props.retentionDays() + " 天），已跳过 " + skipped + " 天（"
                            + start + " ~ " + floor.minusDays(1) + "，WIB），从 " + floor + " 起续算。\n"
                            + "影响：被跳过日期的明细已清理或即将清理，无法再汇总。");
                    start = floor;
                }
                // 定时续算的区间按构造与进度相连（含保留期截断：被跳过的天明确放弃）
                plan.put(f.name(), new Range(start, asOf, start.minusDays(1)));
            }
            return execute(plan, asOf, ctx);
        });
    }

    /**
     * 手动重跑 {@code [from, to]}（调用方须先用 {@link #validateRerun} 校验）。{@code familyNames} 为空 = 全部。绝不抛。
     * 进度只前进不倒退：重跑旧日期不会让 {@code last_done_date} 变小；也不跳缺口：只有与现有进度相连的日期才推进，
     * 重跑一段比 {@code last_done_date + 1} 更晚的日期只写数据、不推进进度，缺口留给定时续算补上。
     */
    public RunResult rerun(LocalDate from, LocalDate to, Collection<String> familyNames, LocalDate asOf) {
        Set<String> selected = familyNames == null || familyNames.isEmpty() ? null : new LinkedHashSet<>(familyNames);
        return guarded("rerun", () -> {
            RollupContext ctx = prepare(asOf, clock.instant());
            if (ctx == null) {
                return RunResult.of(Outcome.FAILED);
            }
            Map<String, Progress> prog;
            try {
                prog = progress.findAll();
            } catch (RuntimeException e) {
                prepareFailed(asOf, "读 agg_progress", e);
                return RunResult.of(Outcome.FAILED);
            }
            Map<String, Range> plan = new LinkedHashMap<>();
            for (RollupFamily f : families) {
                if (selected == null || selected.contains(f.name())) {
                    Progress p = prog.get(f.name());
                    plan.put(f.name(), new Range(from, to, p == null ? null : p.lastDoneDate()));
                }
            }
            log.info("analytics rollup rerun: from={} to={} families={}", from, to, plan.keySet());
            return execute(plan, asOf, ctx);
        });
    }

    // ---------------------------------------------------------------- 执行

    private RunResult guarded(String kind, Supplier<RunResult> body) {
        if (!props.enabled() || !state.isEnabled()) {
            log.info("analytics rollup {} skipped: enabled={} pipeline={}", kind, props.enabled(),
                    state.reason().map(Enum::name).orElse("ENABLED"));
            return RunResult.of(Outcome.SKIPPED_DISABLED);
        }
        if (!runLock.tryLock()) {
            log.info("analytics rollup {} skipped: another rollup is running in this JVM", kind);
            return RunResult.of(Outcome.SKIPPED_BUSY);
        }
        try {
            long t0 = System.nanoTime();
            RunResult r = body.get();
            log.info("analytics rollup {} finished: outcome={} units={} failed={} skipped={} ms={}", kind, r.outcome(),
                    r.units(), r.failedFamilies(), r.skippedFamilies(), (System.nanoTime() - t0) / 1_000_000);
            return r;
        } catch (RuntimeException e) {
            // 兜底：body 内部已逐单元捕获，这里只防编程错误冒出 @Scheduled
            log.warn("analytics rollup {} crashed: {}", kind, describe(e));
            return RunResult.of(Outcome.FAILED);
        } finally {
            runLock.unlock();
        }
    }

    /** 运行开始：读一次业务库排除用户集合（任何埋点事务之外）。失败 → 告警并返回 null。 */
    private RollupContext prepare(LocalDate asOf, Instant runAt) {
        try {
            RollupContext ctx = RollupContext.load(businessJdbc, jdbc, props, asOf, runAt);
            log.info("analytics rollup prepared: asOf={} excludedUsers={}", asOf, ctx.excludedUserCount());
            return ctx;
        } catch (RuntimeException e) {
            prepareFailed(asOf, "读业务库排除用户", e);
            return null;
        }
    }

    private void prepareFailed(LocalDate asOf, String step, RuntimeException e) {
        String cause = describe(e);
        log.warn("analytics rollup prepare failed: step={} cause={}", step, cause);
        alert(ALERT_PREPARE + asOf, "【埋点汇总未运行】环境 " + appEnv + "\n"
                + "步骤：" + step + "\n原因：" + cause + "\n"
                + "影响：本轮所有指标族未计算，进度不推进；下一次运行自动补上缺的日期。");
    }

    private RunResult execute(Map<String, Range> plan, LocalDate asOf, RollupContext ctx) {
        Set<String> failed = new LinkedHashSet<>();
        Set<String> skipped = new LinkedHashSet<>();
        int units = 0;
        for (RollupFamily f : families) {
            Range range = plan.get(f.name());
            if (range == null) {
                continue;
            }
            Optional<String> badDep = f.dependsOn().stream()
                    .filter(d -> failed.contains(d) || skipped.contains(d)).findFirst();
            if (badDep.isPresent()) {
                skipped.add(f.name());
                log.warn("analytics rollup family skipped: family={} dependsOn={} failed", f.name(), badDep.get());
                continue;
            }
            int done = 0;
            UnitStatus status = UnitStatus.OK;
            LocalDate base = range.progressBase();
            boolean gapLogged = false;
            for (LocalDate d = range.from(); !d.isAfter(range.to()); d = d.plusDays(1)) {
                final LocalDate day = d;
                LocalDate advanceTo = base == null || !day.isAfter(base.plusDays(1)) ? day : null;
                if (advanceTo == null && !gapLogged) {
                    gapLogged = true;
                    log.warn("analytics rollup progress not advanced: family={} lastDone={} from={} (gap left for the "
                            + "scheduled run)", f.name(), base, day);
                }
                status = runUnit(f, day.toString(), advanceTo, ctx, () -> f.prepareDay(day, asOf, ctx),
                        () -> f.computeDay(day, asOf, ctx));
                if (status != UnitStatus.OK) {
                    break;
                }
                if (advanceTo != null && (base == null || day.isAfter(base))) {
                    base = day;
                }
                done++;
            }
            if (status == UnitStatus.OK && f.hasPeriods()) {
                status = runUnit(f, "periods-" + range.to(), null, ctx,
                        () -> f.preparePeriods(range.from(), range.to(), asOf, ctx),
                        () -> f.computePeriods(range.from(), range.to(), asOf, ctx));
                if (status == UnitStatus.OK) {
                    done++;
                }
            }
            units += done;
            if (status == UnitStatus.LOCKED) {
                log.info("analytics rollup stopped: advisory lock held elsewhere (family={})", f.name());
                return new RunResult(Outcome.SKIPPED_LOCKED, units, List.copyOf(failed), List.copyOf(skipped));
            }
            if (status == UnitStatus.FAILED) {
                failed.add(f.name());
            }
        }
        Outcome outcome = failed.isEmpty() && skipped.isEmpty() ? Outcome.DONE : Outcome.FAILED;
        return new RunResult(outcome, units, List.copyOf(failed), List.copyOf(skipped));
    }

    /**
     * 一个单元事务：锁 → 放宽超时 → 临时表 → 计算 → 推进进度（{@code advanceTo} 非空时），同一事务提交。
     * 失败时在事务回滚<b>之后</b>写 {@code last_error} 并告警。
     *
     * <p>{@code prepare}（Story 4.4）在事务<b>之前</b>执行（业务库窄查询 / 埋点库短查询，不占着埋点事务连接）；它抛异常按单元失败处理。
     */
    private UnitStatus runUnit(RollupFamily f, String label, LocalDate advanceTo, RollupContext ctx, Runnable prepare,
            Runnable work) {
        long t0 = System.nanoTime();
        try {
            prepare.run();
            tx.executeWithoutResult(s -> {
                Boolean locked = jdbc.queryForObject(LOCK_SQL, Boolean.class, AnalyticsLockKeys.NAMESPACE,
                        AnalyticsLockKeys.ROLLUP);
                if (!Boolean.TRUE.equals(locked)) {
                    throw new LockedException();
                }
                jdbc.execute("SET LOCAL statement_timeout = '" + props.statementTimeout() + "'");
                ctx.createExcludedUsersTable();
                work.run();
                if (advanceTo != null) {
                    progress.markDone(f.name(), advanceTo, ctx.runAt());
                }
            });
        } catch (LockedException e) {
            return UnitStatus.LOCKED;
        } catch (RuntimeException | Error e) {
            // 到这里单元事务已回滚：在事务外记错误、发告警
            String cause = describe(e);
            log.warn("analytics rollup unit failed: family={} date={} cause={}", f.name(), label, cause);
            try {
                progress.recordError(f.name(), cause, ctx.runAt());
            } catch (RuntimeException ex) {
                log.warn("analytics rollup last_error update failed: family={} cause={}", f.name(),
                        ex.getClass().getSimpleName());
            }
            alert(ALERT_FAILED + f.name() + ":" + label, "【埋点汇总失败】环境 " + appEnv + "\n"
                    + "指标族：" + f.name() + "\n日期：" + label + "（WIB）\n原因：" + cause + "\n"
                    + "影响：该指标族本轮停止，进度不推进（agg_progress.last_error 已记录）；其余指标族照常。\n"
                    + "处理：下一次定时运行自动从缺的日期补算；修好原因后也可插 rollup_rerun_requests 手动重跑"
                    + "（见 runbook「重跑汇总」）。");
            return UnitStatus.FAILED;
        }
        log.info("analytics rollup unit done: family={} date={} ms={}", f.name(), label,
                (System.nanoTime() - t0) / 1_000_000);
        return UnitStatus.OK;
    }

    // ---------------------------------------------------------------- 纯计算（L0）

    /**
     * 续算起点：{@code min(lastDone + 1, asOf − recomputeDays + 1)}；从未成功（{@code lastDone} 为空）→
     * {@code asOf − recomputeDays + 1}。
     */
    static LocalDate startDate(LocalDate lastDone, LocalDate asOf, int recomputeDays) {
        LocalDate recompute = asOf.minusDays(recomputeDays - 1L);
        if (lastDone == null) {
            return recompute;
        }
        LocalDate next = lastDone.plusDays(1);
        return next.isBefore(recompute) ? next : recompute;
    }

    /**
     * 汇总能回到的最早日期 = {@code max(asOf − retentionDays, WIB 今天 − 3 个月)}。
     *
     * <p>后者是明细层实际保留的最早分区（{@link RetentionPolicy#detailPartitionsToDrop}：删 {@code < 今天 − 3 个月} 的分区；
     * 今天 = {@code asOf + 1}）。3 个日历月只有 89–92 天，单用 {@code asOf − 92} 会让最早 1–4 天落在已删分区上：
     * 重跑它们会读到空明细、把原本正确的 {@code agg_*} 行改写成 0（代码复审 4-1 R1）。
     */
    static LocalDate floor(LocalDate asOf, int retentionDays) {
        LocalDate byConfig = asOf.minusDays(retentionDays);
        LocalDate byDetail = asOf.plusDays(1).minusMonths(RetentionPolicy.DETAIL_RETENTION_MONTHS);
        return byConfig.isAfter(byDetail) ? byConfig : byDetail;
    }

    /** 重跑请求被拒的原因码。 */
    public static final String REJECT_OUT_OF_RANGE = "OUT_OF_RANGE";
    public static final String REJECT_UNKNOWN_FAMILY = "UNKNOWN_FAMILY";

    /**
     * 校验手动重跑参数：{@code from ≤ to ≤ asOf}、{@code from ≥ asOf − retentionDays}、指标族均已注册。
     *
     * @return 空 = 通过；否则为拒绝原因码
     */
    static Optional<String> validateRerun(LocalDate from, LocalDate to, LocalDate asOf, int retentionDays,
            Collection<String> requested, Collection<String> known) {
        if (from == null || to == null || from.isAfter(to) || to.isAfter(asOf)
                || from.isBefore(floor(asOf, retentionDays))) {
            return Optional.of(REJECT_OUT_OF_RANGE);
        }
        if (requested != null && !known.containsAll(requested)) {
            return Optional.of(REJECT_UNKNOWN_FAMILY);
        }
        return Optional.empty();
    }

    /** 按 {@code dependsOn} 拓扑排序（同层保持注册顺序）。重名、未知依赖、循环依赖 → {@link IllegalStateException}。 */
    static List<RollupFamily> orderByDependencies(List<RollupFamily> input) {
        Map<String, RollupFamily> byName = new LinkedHashMap<>();
        for (RollupFamily f : input) {
            if (byName.put(f.name(), f) != null) {
                throw new IllegalStateException("指标族重名：" + f.name());
            }
        }
        for (RollupFamily f : input) {
            for (String d : f.dependsOn()) {
                if (!byName.containsKey(d)) {
                    throw new IllegalStateException("指标族 " + f.name() + " 依赖未注册的指标族 " + d);
                }
            }
        }
        List<RollupFamily> out = new ArrayList<>();
        Map<String, Integer> mark = new HashMap<>();
        for (RollupFamily f : byName.values()) {
            visit(f, byName, mark, out);
        }
        return List.copyOf(out);
    }

    private static void visit(RollupFamily f, Map<String, RollupFamily> byName, Map<String, Integer> mark,
            List<RollupFamily> out) {
        Integer m = mark.get(f.name());
        if (m != null) {
            if (m == 1) {
                throw new IllegalStateException("指标族循环依赖：" + f.name());
            }
            return;
        }
        mark.put(f.name(), 1);
        for (String d : f.dependsOn()) {
            visit(byName.get(d), byName, mark, out);
        }
        mark.put(f.name(), 2);
        out.add(f);
    }

    /** 异常类名链（不含 message：可能带 SQL / 参数值）。 */
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

    private void alert(String dedupeKey, String text) {
        try {
            alerts.send(OpsChannel.TECH, dedupeKey, text);
        } catch (RuntimeException e) {
            log.warn("analytics rollup alert failed: {}", e.getClass().getSimpleName());
        }
    }
}

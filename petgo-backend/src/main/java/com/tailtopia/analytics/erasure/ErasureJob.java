package com.tailtopia.analytics.erasure;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsLockKeys;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.store.DetailPartitions;
import com.tailtopia.analytics.store.EventTimeCorrector;
import com.tailtopia.analytics.store.RawPartitionPlanner;
import com.tailtopia.analytics.store.RetentionPolicy;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
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
 * 注销擦除（analytics-pipeline Story 1.8，架构 A10，FR-15）：每晚 WIB 02:00（早于 03:00 汇总）把已注销用户在埋点库里的身份脱钩。
 *
 * <h2>输入：业务库 outbox</h2>
 * 待处理集合 = 业务库 {@code analytics_erasure_outbox} 里 {@code done_at IS NULL} 的行（{@link AnalyticsErasureParticipant}
 * 在注销状态机里写入）。为空直接结束 —— 已处理的请求不再扫描（AC3）。按值匹配用的标识串 = {@code user_id} 十进制、
 * {@code user_key}、以及业务库 {@code users.public_token}（事件清单里 {@code owner_id} 的值是用户公开 token）。
 *
 * <h2>擦除（每一步一个埋点事务）</h2>
 * 事务内先 {@code pg_try_advisory_xact_lock(NAMESPACE, ERASURE)}（拿不到即放弃本轮，不写 {@code done_at}），再
 * {@code SET LOCAL statement_timeout}（{@code analytics.erasure.statement-timeout}，默认 30min）：
 * <ol>
 *   <li>{@code DELETE FROM identity_links WHERE user_id = ANY(ids)}</li>
 *   <li>明细层逐分区：本人行 {@code user_id} / {@code user_key} 置空 + 删清单里的用户标识键；他人行里值指向本人的标识键逐键删除</li>
 *   <li>原始层逐分区（含 {@code events_raw_pdefault}）：同上（原始层没有 {@code user_id} 索引，逐分区全表 UPDATE 是预期做法）</li>
 *   <li>全部成功后回写业务库 {@code done_at}。任何一步失败 → 本轮不写 {@code done_at}、TECH 告警，下轮从头再跑（全部 UPDATE 幂等）</li>
 * </ol>
 * <b>只置空、不删行</b>：匿名 id 上的事件保留为匿名事件（AC4）；<b>不碰任何 {@code agg_*} 汇总表</b>。
 *
 * <h2>注销后补发的旧事件（T5）</h2>
 * App 本地队列最长保留 7 天。对 {@code done_at} 在最近 {@link #LATE_WINDOW} 内的请求，额外只扫描
 * <b>收到时间（UTC 日分区）不早于 {@code done_at} 所在 UTC 日</b>的原始层分区（含 DEFAULT）做同样置空，不重扫更早的分区。
 * 同时对这些请求重删一遍 {@code identity_links}（注销后 access token 有效窗口内补发的 {@code user_identified} 会重建关联）。
 * 明细层由 {@code DetailBuilder} 写入前按 outbox 拦截，永不再出现该用户。
 *
 * <h2>🔴 失败只告警</h2>
 * 管道停用 / 清单未加载 → 跳过并 TECH 告警（擦除承诺 24 小时，不能静默跳过）。运行异常 → WARN（只打异常类名）+ TECH 告警
 * （按 WIB 日去重），绝不抛出 {@code @Scheduled}。日志只打待处理请求数、各层更新行数、耗时，<b>不打</b>
 * {@code user_id} / {@code user_key} / token。
 */
@Component
public class ErasureJob {

    private static final Logger log = LoggerFactory.getLogger(ErasureJob.class);

    /** 失败告警去重键前缀，后接 WIB 日期（story 指定键名）。 */
    static final String ALERT_FAILED = "analytics:erasure-failed:";
    /** 停用跳过告警去重键前缀，后接 WIB 日期。 */
    static final String ALERT_SKIPPED = "analytics:erasure-skipped:";
    /** 迟到事件兜底：只看 {@code done_at} 在这么久之内的请求（App 队列最长 7 天 + 1 天余量）。 */
    public static final Duration LATE_WINDOW = Duration.ofDays(8);
    static final String DEFAULT_STATEMENT_TIMEOUT = "30min";
    /** {@code SET LOCAL} 不能绑定参数，只能拼接：配置值必须形如 {@code 30min} / {@code 1800s} / {@code 600000}。 */
    private static final Pattern TIMEOUT_FORMAT = Pattern.compile("^\\d{1,9}(ms|s|min|h)?$");

    // ---------------------------------------------------------------- 业务库
    static final String PENDING_SQL = "SELECT user_id, user_key, done_at FROM analytics_erasure_outbox "
            + "WHERE done_at IS NULL ORDER BY requested_at, user_id";
    static final String RECENT_DONE_SQL = "SELECT user_id, user_key, done_at FROM analytics_erasure_outbox "
            + "WHERE done_at IS NOT NULL AND done_at > ? ORDER BY done_at, user_id";
    static final String PUBLIC_TOKENS_SQL = "SELECT id, public_token FROM users WHERE id = ANY(?)";
    static final String MARK_DONE_SQL =
            "UPDATE analytics_erasure_outbox SET done_at = now() WHERE user_id = ANY(?) AND done_at IS NULL";

    // ---------------------------------------------------------------- 埋点库
    static final String LOCK_SQL = "SELECT pg_try_advisory_xact_lock(?, ?)";
    static final String DELETE_LINKS_SQL = "DELETE FROM identity_links WHERE user_id = ANY(?)";
    static final String RAW_PARTITIONS_SQL = "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid "
            + "WHERE i.inhparent = '" + RawPartitionPlanner.PARENT + "'::regclass";
    static final String EXISTS_SQL = "SELECT to_regclass(?::text) IS NOT NULL";

    /** 一个擦除对象。{@code publicToken} 取自业务库 {@code users}（可能为空）；{@code doneAt} 待处理时为空。 */
    record Target(long userId, String userKey, String publicToken, Instant doneAt) {

        Target withPublicToken(String token) {
            return new Target(userId, userKey, token, doneAt);
        }
    }

    /** 一轮的结局。 */
    public enum Outcome {
        /** 管道停用 / 清单未加载，未运行（已告警）。 */
        SKIPPED_DISABLED,
        /** 没拿到锁（别的实例在跑），本轮放弃，未写 {@code done_at}。 */
        SKIPPED_LOCKED,
        /** 运行完成（可能什么都没做：没有待处理请求）。 */
        DONE,
        /** 运行异常（已告警，未向外抛；未写 {@code done_at}）。 */
        FAILED
    }

    /**
     * 一轮汇总（只有计数）。
     *
     * @param pending       本轮待处理请求数
     * @param late          本轮迟到事件兜底覆盖的已完成请求数
     * @param linksDeleted  删除的 {@code identity_links} 行
     * @param detailRows    明细层被置空 / 剥标识的行
     * @param rawRows       原始层被置空 / 剥标识的行（含迟到兜底）
     */
    public record RunResult(Outcome outcome, int pending, int late, long linksDeleted, long detailRows, long rawRows) {

        static RunResult of(Outcome outcome) {
            return new RunResult(outcome, 0, 0, 0, 0, 0);
        }
    }

    /** 某一步没拿到锁：本轮放弃。 */
    private static final class LockedException extends RuntimeException {

        LockedException() {
            super(null, null, false, false);
        }
    }

    private final AnalyticsPipelineState state;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcTemplate businessJdbc;
    private final EventCatalog catalog;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final String statementTimeout;
    private final Clock clock;

    @Autowired
    public ErasureJob(AnalyticsPipelineState state,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) TransactionTemplate analyticsTransactionTemplate,
            JdbcTemplate businessJdbcTemplate,
            EventCatalog catalog,
            OpsAlertService alerts,
            @Value("${analytics.app-env:dev}") String appEnv,
            @Value("${analytics.erasure.statement-timeout:" + DEFAULT_STATEMENT_TIMEOUT + "}") String statementTimeout) {
        this(state, analyticsJdbcTemplate, analyticsTransactionTemplate, businessJdbcTemplate, catalog, alerts, appEnv,
                statementTimeout, Clock.systemUTC());
    }

    /** 测试用：注入时钟、mock 告警。 */
    ErasureJob(AnalyticsPipelineState state, JdbcTemplate jdbc, TransactionTemplate tx, JdbcTemplate businessJdbc,
            EventCatalog catalog, OpsAlertService alerts, String appEnv, String statementTimeout, Clock clock) {
        this.state = state;
        this.jdbc = jdbc;
        this.tx = tx;
        this.businessJdbc = businessJdbc;
        this.catalog = catalog;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.statementTimeout = requireValidTimeout(statementTimeout);
        this.clock = clock;
    }

    /** 每晚 WIB 02:00（早于批次 ② 汇总的 03:00）。 */
    @Scheduled(cron = "${analytics.erasure.cron:0 0 2 * * *}", zone = "Asia/Jakarta")
    public void scheduled() {
        runOnce();
    }

    /** 跑一轮：停用跳过并告警，失败告警，绝不抛。 */
    public RunResult runOnce() {
        Instant started = clock.instant();
        if (!state.isEnabled() || !catalog.isLoaded()) {
            String why = state.reason().map(Enum::name).orElse("CATALOG_NOT_LOADED");
            log.warn("analytics erasure skipped: pipeline disabled reason={}", why);
            alert(ALERT_SKIPPED, "【埋点注销擦除跳过】环境 " + appEnv + "\n"
                    + "原因：埋点管道停用（" + why + "）\n"
                    + "影响：待擦除请求保留在业务库 analytics_erasure_outbox，未处理；注销「24 小时内脱钩」承诺有超时风险。"
                    + "当天（WIB）只告警一次。\n"
                    + "处理：修复埋点库 / 事件清单后重启后端，下一次每晚任务自动补处理。");
            return RunResult.of(Outcome.SKIPPED_DISABLED);
        }
        RunResult result;
        try {
            result = process(started);
        } catch (LockedException e) {
            result = RunResult.of(Outcome.SKIPPED_LOCKED);
        } catch (RuntimeException | Error e) {
            String cause = describe(e);
            log.warn("analytics erasure failed: {}", cause);
            alert(ALERT_FAILED, "【埋点注销擦除失败】环境 " + appEnv + "\n"
                    + "原因：" + cause + "\n"
                    + "影响：本轮未回写 done_at，待擦除请求保留；已执行的步骤幂等，下一次每晚任务从头重跑。当天（WIB）只告警一次。\n"
                    + "处理：检查埋点库可用性 / 写入账号权限、业务库 analytics_erasure_outbox；看后端日志 analytics erasure failed。");
            result = RunResult.of(Outcome.FAILED);
        }
        if (result.pending() > 0 || result.late() > 0 || result.outcome() != Outcome.DONE) {
            log.info("analytics erasure: outcome={} pending={} late={} links={} detailRows={} rawRows={} elapsedMs={}",
                    result.outcome(), result.pending(), result.late(), result.linksDeleted(), result.detailRows(),
                    result.rawRows(), Duration.between(started, clock.instant()).toMillis());
        }
        return result;
    }

    private RunResult process(Instant started) {
        List<Target> pendingRows = loadTargets(PENDING_SQL);
        List<Target> lateRows = loadTargets(RECENT_DONE_SQL, utc(started.minus(LATE_WINDOW)));
        if (pendingRows.isEmpty() && lateRows.isEmpty()) {
            return new RunResult(Outcome.DONE, 0, 0, 0, 0, 0);
        }
        Map<Long, String> tokens = publicTokens(pendingRows, lateRows);
        List<Target> pending = withTokens(pendingRows, tokens);
        List<Target> late = withTokens(lateRows, tokens);
        String[] idKeys = catalog.userIdentifierKeys().toArray(String[]::new);

        long links = 0;
        long detailRows = 0;
        long rawRows = 0;
        if (!pending.isEmpty()) {
            Long[] ids = userIds(pending);
            links = inErasureTx(j -> j.update(DELETE_LINKS_SQL,
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids))));
            for (LocalDate d : new TreeSet<>(DetailPartitions.builtDates(jdbc))) {
                String p = DetailPartitions.partitionName(d);
                detailRows += inErasureTx(j -> erasePartition(j, p, pending, idKeys));
            }
            for (String p : rawPartitions(rawPartitionNames())) {
                rawRows += inErasureTx(j -> erasePartition(j, p, pending, idKeys));
            }
            businessJdbc.update(MARK_DONE_SQL,
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)));
        }
        if (!late.isEmpty()) {
            // 复审 2026-11-12：注销后 access token 仍有效的窗口内（JWT 最长 15 分钟、无注销即失效名单），设备补发的
            // user_identified 会重新写出该用户的 identity_links 行；迟到兜底只扫原始层会让这行永久留下。关联表走 user_id 索引，
            // 顺带重删一遍（无行时 0 行）。
            Long[] lateIds = userIds(late);
            links += inErasureTx(j -> j.update(DELETE_LINKS_SQL,
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", lateIds))));
            List<String> names = rawPartitionNames();
            for (Map.Entry<String, List<Target>> e : latePartitions(RetentionPolicy.rawPartitionDates(names),
                    names.contains(RawPartitionPlanner.DEFAULT_PARTITION), late).entrySet()) {
                String p = e.getKey();
                List<Target> targets = e.getValue();
                rawRows += inErasureTx(j -> erasePartition(j, p, targets, idKeys));
            }
        }
        return new RunResult(Outcome.DONE, pending.size(), late.size(), links, detailRows, rawRows);
    }

    /**
     * 在一个埋点事务内：拿锁（拿不到 → {@link LockedException}）→ 放宽语句超时 → 执行。
     */
    private long inErasureTx(ToLongFunction<JdbcTemplate> work) {
        Long n = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject(LOCK_SQL, Boolean.class, AnalyticsLockKeys.NAMESPACE,
                    AnalyticsLockKeys.ERASURE);
            if (!Boolean.TRUE.equals(locked)) {
                throw new LockedException();
            }
            jdbc.execute("SET LOCAL statement_timeout = '" + statementTimeout + "'");
            return work.applyAsLong(jdbc);
        });
        return n == null ? 0 : n;
    }

    /**
     * 擦一个分区（调用方的埋点事务内）。分区在列出之后被保留期任务删掉 → 跳过（行已不存在）。
     *
     * @return 更新的行数（本人行 + 他人行里被剥标识的行）
     */
    static long erasePartition(JdbcTemplate j, String partition, List<Target> targets, String[] idKeys) {
        if (!Boolean.TRUE.equals(j.queryForObject(EXISTS_SQL, Boolean.class, partition))) {
            return 0;
        }
        Long[] ids = userIds(targets);
        String[] keys = userKeys(targets);
        String[] values = identifierValues(targets).toArray(String[]::new);
        long n = j.update(ownRowsSql(partition), ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", idKeys));
            ps.setArray(2, ps.getConnection().createArrayOf("bigint", ids));
            ps.setArray(3, ps.getConnection().createArrayOf("text", keys));
        });
        for (String k : idKeys) {
            n += j.update(valueStripSql(partition), ps -> {
                ps.setString(1, k);
                ps.setString(2, k);
                ps.setArray(3, ps.getConnection().createArrayOf("text", values));
            });
        }
        return n;
    }

    /** 本人行：置空身份列 + 删清单里全部用户标识键（{@code jsonb - text[]}）。分区名须已校验。 */
    static String ownRowsSql(String partition) {
        return "UPDATE " + partition + " SET user_id = NULL, user_key = NULL, properties = properties - ?::text[] "
                + "WHERE user_id = ANY(?) OR user_key = ANY(?)";
    }

    /** 他人行：某个用户标识键的值指向被注销用户 → 删掉该键。分区名须已校验。 */
    static String valueStripSql(String partition) {
        return "UPDATE " + partition + " SET properties = properties - ?::text WHERE properties ->> ?::text = ANY(?)";
    }

    // ---------------------------------------------------------------- 纯计算（L0）

    /** 按值匹配用的标识串：{@code user_id} 十进制、{@code user_key}、公开 token（有则带）。去重、保序。 */
    static Set<String> identifierValues(List<Target> targets) {
        Set<String> out = new LinkedHashSet<>();
        for (Target t : targets) {
            out.add(Long.toString(t.userId()));
            if (t.userKey() != null && !t.userKey().isEmpty()) {
                out.add(t.userKey());
            }
            if (t.publicToken() != null && !t.publicToken().isEmpty()) {
                out.add(t.publicToken());
            }
        }
        return out;
    }

    /**
     * 迟到事件兜底要扫的原始层分区 → 该分区要擦的请求：日分区 {@code d} 只对 {@code done_at} 所在 UTC 日 {@code <= d} 的请求生效
     * （更早的分区在擦除时已处理过，不重扫）；DEFAULT 分区（有行时日期未知）对全部请求生效。按分区名升序，DEFAULT 在最后。
     */
    static Map<String, List<Target>> latePartitions(Set<LocalDate> rawDates, boolean hasDefault, List<Target> late) {
        Map<String, List<Target>> out = new LinkedHashMap<>();
        for (LocalDate d : new TreeSet<>(rawDates)) {
            List<Target> ts = new ArrayList<>();
            for (Target t : late) {
                if (!RawPartitionPlanner.utcDay(t.doneAt()).isAfter(d)) {
                    ts.add(t);
                }
            }
            if (!ts.isEmpty()) {
                out.put(RawPartitionPlanner.partitionName(d), List.copyOf(ts));
            }
        }
        if (hasDefault && !late.isEmpty()) {
            out.put(RawPartitionPlanner.DEFAULT_PARTITION, List.copyOf(late));
        }
        return out;
    }

    /** 校验 {@code statement_timeout} 配置值（只能拼进 {@code SET LOCAL}）。 */
    static String requireValidTimeout(String value) {
        String v = value == null ? "" : value.strip();
        if (!TIMEOUT_FORMAT.matcher(v).matches()) {
            throw new IllegalArgumentException("analytics.erasure.statement-timeout 格式非法（例：30min / 1800s）");
        }
        return v;
    }

    // ---------------------------------------------------------------- 库访问

    private List<Target> loadTargets(String sql, Object... args) {
        return businessJdbc.query(sql, (ResultSet rs, int n) -> {
            OffsetDateTime done = rs.getObject(3, OffsetDateTime.class);
            return new Target(rs.getLong(1), rs.getString(2), null, done == null ? null : done.toInstant());
        }, args);
    }

    /** 业务库 {@code users.public_token}（不可更新，注销匿名化后仍保留）：事件清单 {@code owner_id} 的值就是它。 */
    private Map<Long, String> publicTokens(List<Target> a, List<Target> b) {
        Set<Long> ids = new LinkedHashSet<>();
        a.forEach(t -> ids.add(t.userId()));
        b.forEach(t -> ids.add(t.userId()));
        Long[] arr = ids.toArray(Long[]::new);
        Map<Long, String> out = new HashMap<>();
        businessJdbc.query(PUBLIC_TOKENS_SQL, ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", arr)),
                (ResultSet rs) -> {
                    out.put(rs.getLong(1), rs.getString(2));
                });
        return out;
    }

    private static List<Target> withTokens(List<Target> targets, Map<Long, String> tokens) {
        List<Target> out = new ArrayList<>(targets.size());
        for (Target t : targets) {
            out.add(t.withPublicToken(tokens.get(t.userId())));
        }
        return List.copyOf(out);
    }

    /** 当前挂在 {@code events_raw} 上的子表名（查 {@code pg_inherits}，未校验）。 */
    private List<String> rawPartitionNames() {
        return jdbc.queryForList(RAW_PARTITIONS_SQL, String.class);
    }

    /**
     * 要擦的原始层分区名（由已校验的命名规则重新生成，不直接用库里读出的名字拼 SQL）：日分区升序，DEFAULT 在最后（若存在）。
     */
    static List<String> rawPartitions(List<String> attachedNames) {
        List<String> out = new ArrayList<>();
        for (LocalDate d : new TreeSet<>(RetentionPolicy.rawPartitionDates(attachedNames))) {
            out.add(RawPartitionPlanner.partitionName(d));
        }
        if (attachedNames.contains(RawPartitionPlanner.DEFAULT_PARTITION)) {
            out.add(RawPartitionPlanner.DEFAULT_PARTITION);
        }
        return List.copyOf(out);
    }

    private static Long[] userIds(List<Target> targets) {
        return targets.stream().map(Target::userId).toArray(Long[]::new);
    }

    private static String[] userKeys(List<Target> targets) {
        return targets.stream().map(Target::userKey).filter(k -> k != null && !k.isEmpty()).toArray(String[]::new);
    }

    private void alert(String prefix, String text) {
        LocalDate day = EventTimeCorrector.wibDate(clock.instant());
        try {
            alerts.send(OpsChannel.TECH, prefix + day, text);
        } catch (RuntimeException e) {
            log.warn("analytics erasure alert failed: {}", e.getClass().getSimpleName());
        }
    }

    /** 异常类名链（不含 message：可能带连接串 / SQL / 参数值）。 */
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

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}

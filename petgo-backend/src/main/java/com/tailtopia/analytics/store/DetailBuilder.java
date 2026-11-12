package com.tailtopia.analytics.store;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsLockKeys;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.store.ProductionFlag.UserAccount;
import com.tailtopia.analytics.store.ProductionFlag.Verdict;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 原始层 → 明细层增量转换（analytics-pipeline Story 1.6，架构 D-3 / D-4 / A5 / A9 / FR-9 / FR-13）。
 *
 * <h2>节奏</h2>
 * 每分钟一轮（{@code analytics.detail.fixed-delay-ms}）。一轮内按 {@code received_at} 切时间片循环：
 * {@code lower = pipeline_progress.last_received_at}，{@code upper = min(lower + 5 分钟, now − 2 分钟)}；
 * {@code upper <= lower} 即追平，一轮最多跑约 {@link #RUN_BUDGET}，剩下的下一轮继续。
 * 进度落后超过 {@link #CATCH_UP_GAP}（初始游标 1970 年、长时间停机）时先跳到下一条待处理行的 {@code received_at}，不逐片空转。
 *
 * <h2>一个时间片 = 一个埋点事务</h2>
 * {@code pg_try_advisory_xact_lock(NAMESPACE, DETAIL_BUILDER)}（拿不到即结束本轮）→ 复核进度未被别人推进 → 读原始层
 * （批内 {@code DISTINCT ON (message_id)} 取最早收到的那条，{@code unknown_event} 不转）→ 时间校正 / 瘦身 / 生产标记 →
 * 跨日查重（{@code event_date} 前后各 1 天）→ {@code INSERT … ON CONFLICT (event_date, message_id) DO NOTHING} →
 * 推进进度 → 提交。数据与进度同生共死。
 *
 * <h2>业务库</h2>
 * 生产标记要用户的 {@code role} / {@code account_type}：在开埋点事务<b>之前</b>，先在埋点库取本片的 {@code user_id}，
 * 再用业务 {@code @Primary} {@link JdbcTemplate} 一次窄查询 {@code users}。两个库之间不做跨库 SQL，也不在埋点事务里查业务库。
 *
 * <h2>🔴 失败只告警</h2>
 * 管道停用时直接返回。任何异常（含某行 {@code event_date} 的分区不存在 → 整片回滚、不推进进度）→ WARN（只打异常类名）+
 * TECH 告警（按 WIB 日去重），绝不抛出 {@code @Scheduled}。告警一律在埋点事务返回后发。
 *
 * <h2>重算（Story 1.7）</h2>
 * {@link DetailRecomputeJob} 复用本类同一套「转换 → 查重 → 写入」（{@code writeRows}）：{@link #recomputeSlice} 按<b>当前</b>清单
 * 过滤事件名、只写请求区间内的行、不推进进度。存在 {@code RUNNING} 的重算请求时，实时增量在锁内看到即整片跳过
 * （{@link Outcome#SKIPPED_RECOMPUTING}）—— 重算期间明细新鲜度暂停。
 *
 * <h2>注销擦除拦截（Story 1.8，A10 / FR-15）</h2>
 * 开埋点事务之前，再用业务库窄查询 {@code analytics_erasure_outbox}（{@link #ERASED_SQL}）：
 * <ul>
 *   <li>本片 {@code user_id} 命中 outbox（<b>任何状态</b>）的行 → 写明细前置空 {@code user_id} / {@code user_key}，并剥掉清单里
 *       全部用户标识键 —— 注销后设备补发的旧事件，明细层永不再出现该用户（匿名 id 保留，成为匿名事件）。</li>
 *   <li>待处理或 {@code done_at} 在最近 {@link #ERASED_VALUE_WINDOW} 内的请求：他人事件里用户标识键的值等于被注销用户的
 *       {@code user_id} 十进制 / {@code user_key} / 公开 token → 删掉该键（与 {@code ErasureJob} 按值匹配同口径）。</li>
 * </ul>
 * 实时增量与重算共用同一套拦截。
 *
 * <p>日志只打条数，不打事件内容、{@code user_id} / {@code user_key} / 匿名 id。埋点库只经 {@code @Qualifier("analytics")}
 * 的 {@link JdbcTemplate} / {@link TransactionTemplate} 访问，禁止 JPA。
 */
@Component
public class DetailBuilder {

    private static final Logger log = LoggerFactory.getLogger(DetailBuilder.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** {@code pipeline_progress.pipeline}。 */
    static final String PIPELINE = "detail";
    /** 时间片长度。 */
    static final Duration SLICE = Duration.ofMinutes(5);
    /** 只处理到 {@code now − 2 分钟}：给 {@code IngestFlusher} 入队到提交留余量。 */
    static final Duration LAG = Duration.ofMinutes(2);
    /** 单轮最长运行时间（略小于调度间隔）。 */
    static final Duration RUN_BUDGET = Duration.ofSeconds(50);
    /** 进度落后超过这么多时，先跳到下一条待处理行。 */
    static final Duration CATCH_UP_GAP = Duration.ofHours(1);
    /** 平台 / 版本 / 机型列宽（varchar(64)），超长截断：客户端值不可信，不能让一行拖垮整片。 */
    static final int MAX_TEXT = 64;

    static final String ALERT_FAILED = "analytics-detail-failed:";

    static final String PROGRESS_SQL = "SELECT last_received_at FROM pipeline_progress WHERE pipeline = ?";
    static final String UPDATE_PROGRESS_SQL =
            "UPDATE pipeline_progress SET last_received_at = ?, updated_at = now() WHERE pipeline = ?";
    static final String NEXT_RECEIVED_SQL =
            "SELECT min(received_at) FROM events_raw WHERE received_at > ? AND received_at <= ?";
    static final String SLICE_USER_IDS_SQL = "SELECT DISTINCT user_id FROM events_raw "
            + "WHERE received_at > ? AND received_at <= ? AND unknown_event = false AND user_id IS NOT NULL";
    static final String READ_SQL = "SELECT DISTINCT ON (message_id) message_id, event, client_ts, sent_at, received_at, "
            + "anonymous_id, user_key, user_id, source, properties::text, context::text FROM events_raw "
            + "WHERE received_at > ? AND received_at <= ? AND unknown_event = false ORDER BY message_id, received_at";
    static final String EXISTING_SQL = "SELECT message_id FROM events_detail "
            + "WHERE event_date BETWEEN ? AND ? AND message_id = ANY(?)";
    static final String INSERT_SQL = "INSERT INTO events_detail (message_id, event, client_ts, event_time, event_date, "
            + "is_suspicious, source, anonymous_id, user_id, user_key, platform, app_version, os_version, device_model, "
            + "is_production, properties) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) "
            + "ON CONFLICT (event_date, message_id) DO NOTHING";
    /** 业务库只读窄查询（用户角色与账号类型）。 */
    static final String USERS_SQL = "SELECT id, role, account_type FROM users WHERE id = ANY(?)";
    /** Story 1.7：存在进行中的重算请求时实时增量整片跳过（重算清空分区期间，跨日查重会漏判）。 */
    static final String RECOMPUTE_RUNNING_SQL =
            "SELECT EXISTS (SELECT 1 FROM detail_recompute_requests WHERE status = 'RUNNING')";
    /** Story 1.7 重算：不看原始层 {@code unknown_event} 标记，按<b>当前</b>清单登记的事件名过滤（补登记的事件同样转入）。 */
    static final String RECOMPUTE_USER_IDS_SQL = "SELECT DISTINCT user_id FROM events_raw "
            + "WHERE received_at > ? AND received_at <= ? AND event = ANY(?) AND user_id IS NOT NULL";
    /**
     * Story 1.8：业务库 outbox 里本片用户（任何状态）+ 近期请求（按值匹配用）的标识。
     * {@code users.public_token} 不可更新、注销匿名化后仍保留（事件清单 {@code owner_id} 的值就是它）。
     */
    static final String ERASED_SQL = "SELECT o.user_id, o.user_key, u.public_token FROM analytics_erasure_outbox o "
            + "LEFT JOIN users u ON u.id = o.user_id "
            + "WHERE o.user_id = ANY(?) OR o.done_at IS NULL OR o.done_at > ?";
    /** Story 1.8：按值匹配只看待处理或 {@code done_at} 在这么久内的请求（App 队列最长 7 天 + 1 天余量，同 ErasureJob）。 */
    static final Duration ERASED_VALUE_WINDOW = Duration.ofDays(8);
    static final String RECOMPUTE_READ_SQL = "SELECT DISTINCT ON (message_id) message_id, event, client_ts, sent_at, "
            + "received_at, anonymous_id, user_key, user_id, source, properties::text, context::text FROM events_raw "
            + "WHERE received_at > ? AND received_at <= ? AND event = ANY(?) ORDER BY message_id, received_at";

    /** 原始层一行（{@code DISTINCT ON} 之后）。 */
    record RawRow(String messageId, String event, Instant clientTs, Instant sentAt, Instant receivedAt,
            String anonymousId, String userKey, Long userId, String source, String propertiesJson,
            String contextJson) {
    }

    /** 要写入明细层的一行。 */
    record DetailRow(String messageId, String event, Instant clientTs, Instant eventTime, LocalDate eventDate,
            boolean suspicious, String source, String anonymousId, Long userId, String userKey, String platform,
            String appVersion, String osVersion, String deviceModel, boolean production, String propertiesJson) {
    }

    /**
     * Story 1.8：已登记注销擦除的用户（来自业务库 outbox）。
     *
     * @param userIds  命中即置空身份列、剥全部用户标识键
     * @param userKeys 同上（按 {@code user_key} 命中）
     * @param values   按值匹配用的标识串（{@code user_id} 十进制 / {@code user_key} / 公开 token）
     */
    record Erased(Set<Long> userIds, Set<String> userKeys, Set<String> values) {

        static final Erased NONE = new Erased(Set.of(), Set.of(), Set.of());

        boolean isEmpty() {
            return userIds.isEmpty() && userKeys.isEmpty() && values.isEmpty();
        }

        boolean matches(Long userId, String userKey) {
            return (userId != null && userIds.contains(userId)) || (userKey != null && userKeys.contains(userKey));
        }
    }

    /** 转换结果：明细行 + 生产标记判定（计数用）。 */
    record Mapped(DetailRow row, Verdict verdict) {
    }

    /** 一轮的结局。 */
    public enum Outcome {
        /** 管道停用，未运行。 */
        SKIPPED_DISABLED,
        /** 没拿到锁（别的实例 / 线程在跑），一片也没处理。 */
        SKIPPED_LOCKED,
        /** Story 1.7：有进行中的重算请求（{@code RUNNING}），本轮暂停（明细新鲜度暂停，重算完自动续上）。 */
        SKIPPED_RECOMPUTING,
        /** 运行完成（可能 0 片：已追平）。 */
        DONE,
        /** 运行异常（已告警，未向外抛；出错那一片已回滚，进度停在它之前）。 */
        FAILED
    }

    /** 一轮汇总（只有条数）。 */
    public record RunResult(Outcome outcome, int slices, long read, long written, long duplicates, long suspicious,
            long nonProduction, long unknownUsers) {

        static RunResult of(Outcome outcome) {
            return new RunResult(outcome, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /** 一个时间片的结局。 */
    enum SliceStatus { PROCESSED, LOCKED, CAUGHT_UP, MOVED_ELSEWHERE, RECOMPUTING }

    record SliceResult(SliceStatus status, long read, long written, long duplicates, long suspicious,
            long nonProduction, long unknownUsers) {

        static SliceResult of(SliceStatus status) {
            return new SliceResult(status, 0, 0, 0, 0, 0, 0);
        }
    }

    /** 某行的 {@code event_date} 没有对应分区（理论上不会发生）：整片回滚、告警、不推进进度。 */
    static final class DetailPartitionMissingException extends RuntimeException {

        private final transient LocalDate date;

        DetailPartitionMissingException(LocalDate date) {
            super("明细层缺分区：" + date);
            this.date = date;
        }

        LocalDate date() {
            return date;
        }
    }

    private final AnalyticsPipelineState state;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcTemplate businessJdbc;
    private final EventCatalog catalog;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final Clock clock;
    private final Duration runBudget;

    @Autowired
    public DetailBuilder(AnalyticsPipelineState state,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) TransactionTemplate analyticsTransactionTemplate,
            JdbcTemplate businessJdbcTemplate,
            EventCatalog catalog,
            OpsAlertService alerts,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(state, analyticsJdbcTemplate, analyticsTransactionTemplate, businessJdbcTemplate, catalog, alerts, appEnv,
                Clock.systemUTC(), RUN_BUDGET);
    }

    /** 测试用：注入时钟、单轮预算、mock 告警。 */
    DetailBuilder(AnalyticsPipelineState state, JdbcTemplate jdbc, TransactionTemplate tx, JdbcTemplate businessJdbc,
            EventCatalog catalog, OpsAlertService alerts, String appEnv, Clock clock, Duration runBudget) {
        this.state = state;
        this.jdbc = jdbc;
        this.tx = tx;
        this.businessJdbc = businessJdbc;
        this.catalog = catalog;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.clock = clock;
        this.runBudget = runBudget;
    }

    /** 每分钟一轮（上一轮结束后再等 {@code fixed-delay-ms}）。 */
    @Scheduled(fixedDelayString = "${analytics.detail.fixed-delay-ms:60000}",
            initialDelayString = "${analytics.detail.initial-delay-ms:60000}")
    public void scheduled() {
        runOnce();
    }

    /** 跑一轮：停用跳过，失败告警，绝不抛。 */
    public RunResult runOnce() {
        if (!state.isEnabled()) {
            return RunResult.of(Outcome.SKIPPED_DISABLED);
        }
        Instant started = clock.instant();
        int slices = 0;
        long read = 0;
        long written = 0;
        long duplicates = 0;
        long suspicious = 0;
        long nonProduction = 0;
        long unknownUsers = 0;
        Outcome outcome = Outcome.DONE;
        try {
            Set<LocalDate> built = DetailPartitions.builtDates(jdbc);
            while (Duration.between(started, clock.instant()).compareTo(runBudget) < 0) {
                SliceResult s = runSlice(built);
                if (s.status() == SliceStatus.LOCKED || s.status() == SliceStatus.RECOMPUTING) {
                    if (slices == 0) {
                        outcome = s.status() == SliceStatus.LOCKED ? Outcome.SKIPPED_LOCKED
                                : Outcome.SKIPPED_RECOMPUTING;
                    }
                    break;
                }
                if (s.status() != SliceStatus.PROCESSED) {
                    break;
                }
                slices++;
                read += s.read();
                written += s.written();
                duplicates += s.duplicates();
                suspicious += s.suspicious();
                nonProduction += s.nonProduction();
                unknownUsers += s.unknownUsers();
            }
        } catch (DetailPartitionMissingException e) {
            outcome = Outcome.FAILED;
            log.warn("analytics detail build failed: missing detail partition date={}", e.date());
            alertFailed("明细层缺分区（event_date=" + e.date() + "）", "检查 PartitionMaintenanceJob 的明细预建日志与告警；"
                    + "建好分区后下一轮自动继续。");
        } catch (RuntimeException | Error e) {
            outcome = Outcome.FAILED;
            String cause = PartitionMaintenanceJob.describe(e);
            log.warn("analytics detail build failed: {}", cause);
            alertFailed(cause, "检查埋点库可用性、写入账号权限 / 连接数（埋点池 3 条）、业务库 users 查询；看后端日志 "
                    + "analytics detail build failed。");
        }
        if (read > 0 || outcome == Outcome.FAILED) {
            log.info("analytics detail built: outcome={} slices={} read={} written={} duplicates={} suspicious={} "
                    + "nonProduction={} unknownUsers={}", outcome, slices, read, written, duplicates, suspicious,
                    nonProduction, unknownUsers);
        }
        return new RunResult(outcome, slices, read, written, duplicates, suspicious, nonProduction, unknownUsers);
    }

    /** 处理一个时间片（一个埋点事务）。 */
    SliceResult runSlice(Set<LocalDate> built) {
        Instant lower = readProgress();
        Instant limit = clock.instant().minus(LAG).truncatedTo(ChronoUnit.MICROS);
        Instant upper = sliceUpper(lower, limit,
                () -> queryInstant(NEXT_RECEIVED_SQL, utc(lower), utc(limit)));
        if (upper == null) {
            return SliceResult.of(SliceStatus.CAUGHT_UP);
        }
        List<Long> sliceUserIds = jdbc.queryForList(SLICE_USER_IDS_SQL, Long.class, utc(lower), utc(upper));
        Map<Long, UserAccount> users = lookupUsers(sliceUserIds);
        Erased erased = lookupErased(sliceUserIds);
        SliceResult r = tx.execute(status -> processSlice(lower, upper, built, users, erased));
        return r == null ? SliceResult.of(SliceStatus.LOCKED) : r;
    }

    /**
     * 本片上界（纯计算，L0 可测）。本片处理 {@code (lower, upper]}，下界始终是 {@code lower}，所以跳跃只会扩大区间、不会漏行。
     *
     * <ul>
     *   <li>{@code lower >= limit} → {@code null}（已追平）。</li>
     *   <li>落后不超过 {@link #CATCH_UP_GAP}：{@code min(lower + SLICE, limit)}。</li>
     *   <li>落后超过 {@link #CATCH_UP_GAP}：问 {@code nextReceived}（{@code (lower, limit]} 内最早的 {@code received_at}，
     *       无行为 {@code null}）。无行 → 直接到 {@code limit}；有行 → {@code min(next − 1µs + SLICE, limit)}（{@code next} 本身必在区间内）。</li>
     * </ul>
     */
    static Instant sliceUpper(Instant lower, Instant limit, Supplier<Instant> nextReceived) {
        if (!lower.isBefore(limit)) {
            return null;
        }
        Instant base = lower;
        if (Duration.between(lower, limit).compareTo(CATCH_UP_GAP) > 0) {
            // 落后很多：跳到下一条待处理行（之前的区间没有行，读 (lower, upper] 结果不变）
            Instant next = nextReceived.get();
            base = next == null ? limit : max(lower, next.minus(1, ChronoUnit.MICROS));
        }
        Instant upper = min(base.plus(SLICE), limit);
        return upper.isAfter(lower) ? upper : null;
    }

    /** 在调用方的埋点事务内：拿锁 → 复核进度 → 读 → 转换 → 查重 → 写 → 推进进度。 */
    private SliceResult processSlice(Instant lower, Instant upper, Set<LocalDate> built,
            Map<Long, UserAccount> users, Erased erased) {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class,
                AnalyticsLockKeys.NAMESPACE, AnalyticsLockKeys.DETAIL_BUILDER);
        if (!Boolean.TRUE.equals(locked)) {
            return SliceResult.of(SliceStatus.LOCKED);
        }
        // Story 1.7：重算在同一把锁下先置 RUNNING（已提交）再清分区，所以锁内看到 RUNNING 即可保证不与清空交错
        if (Boolean.TRUE.equals(jdbc.queryForObject(RECOMPUTE_RUNNING_SQL, Boolean.class))) {
            return SliceResult.of(SliceStatus.RECOMPUTING);
        }
        if (!lower.equals(readProgress())) {
            return SliceResult.of(SliceStatus.MOVED_ELSEWHERE);
        }
        List<RawRow> raws = jdbc.query(READ_SQL, DetailBuilder::mapRaw, utc(lower), utc(upper));
        SliceResult r = writeRows(raws, built, users, erased, d -> true);
        jdbc.update(UPDATE_PROGRESS_SQL, utc(upper), PIPELINE);
        beforeCommit();
        return r;
    }

    /**
     * 实时增量与重算（Story 1.7）共用的「转换 → 查重 → 写入」。在调用方的埋点事务内、已持有
     * {@link AnalyticsLockKeys#DETAIL_BUILDER} 锁时调用。
     *
     * @param raws  本片原始行（已 {@code DISTINCT ON (message_id)}、已按清单过滤事件名）
     * @param erased Story 1.8：已登记注销擦除的用户（写入前拦截）
     * @param keep  只写 {@code event_date} 满足它的行（实时恒真；重算只写请求区间内的行，区间外的读进来丢掉）。
     *              不满足的行不检查分区是否存在，也不计数
     */
    private SliceResult writeRows(List<RawRow> raws, Set<LocalDate> built, Map<Long, UserAccount> users,
            Erased erased, Predicate<LocalDate> keep) {
        Set<String> idKeys = erased.isEmpty() ? Set.of() : catalog.userIdentifierKeys();
        List<DetailRow> candidates = new ArrayList<>(raws.size());
        long suspicious = 0;
        long nonProduction = 0;
        long unknownUsers = 0;
        for (RawRow raw : raws) {
            Mapped m = toDetail(raw, built, catalog::allowedPropertyKeys, users, erased, idKeys);
            DetailRow row = m.row();
            if (!keep.test(row.eventDate())) {
                continue;
            }
            if (!built.contains(row.eventDate())) {
                throw new DetailPartitionMissingException(row.eventDate());
            }
            candidates.add(row);
            suspicious += row.suspicious() ? 1 : 0;
            nonProduction += row.production() ? 0 : 1;
            unknownUsers += m.verdict() == Verdict.UNKNOWN_USER ? 1 : 0;
        }
        Set<String> existing = existingMessageIds(candidates);
        List<DetailRow> fresh = new ArrayList<>(candidates.size());
        for (DetailRow row : candidates) {
            if (!existing.contains(row.messageId())) {
                fresh.add(row);
            }
        }
        long written = 0;
        if (!fresh.isEmpty()) {
            int[][] counts = jdbc.batchUpdate(INSERT_SQL, fresh, fresh.size(), DetailBuilder::bind);
            for (int[] batch : counts) {
                for (int c : batch) {
                    written += c > 0 ? c : (c == Statement.SUCCESS_NO_INFO ? 1 : 0);
                }
            }
        }
        return new SliceResult(SliceStatus.PROCESSED, candidates.size(), written, candidates.size() - written,
                suspicious, nonProduction, unknownUsers);
    }

    // ---------------------------------------------------------------- Story 1.7：重算（由 DetailRecomputeJob 驱动）

    /** 当前清单登记的全部事件名（任何 status）。重算按它过滤，不看原始层的 {@code unknown_event} 标记。 */
    List<String> registeredEvents() {
        List<String> names = new ArrayList<>();
        for (EventCatalog.EventDefinition e : catalog.events()) {
            names.add(e.name());
        }
        return List.copyOf(names);
    }

    /**
     * 重算一个时间片 {@code (lower, upper]}：一个埋点事务，拿同一把 {@link AnalyticsLockKeys#DETAIL_BUILDER} 锁
     * （<b>阻塞等待</b>，最多 {@link #RECOMPUTE_LOCK_TIMEOUT}：实时增量的单片事务很短），只写
     * {@code event_date ∈ [from, to]} 的行，<b>不推进</b> {@code pipeline_progress}。
     *
     * @param registered {@link #registeredEvents()}（一次重算取一次，整个请求用同一份清单）
     */
    SliceResult recomputeSlice(Instant lower, Instant upper, Set<LocalDate> built, List<String> registered,
            LocalDate from, LocalDate to) {
        String[] events = registered.toArray(String[]::new);
        List<Long> sliceUserIds = jdbc.query(RECOMPUTE_USER_IDS_SQL, ps -> {
            ps.setObject(1, utc(lower));
            ps.setObject(2, utc(upper));
            ps.setArray(3, ps.getConnection().createArrayOf("varchar", events));
        }, (rs, n) -> rs.getLong(1));
        Map<Long, UserAccount> users = lookupUsers(sliceUserIds);
        Erased erased = lookupErased(sliceUserIds);
        return tx.execute(status -> {
            lockDetailBuilderBlocking();
            List<RawRow> raws = jdbc.query(RECOMPUTE_READ_SQL, ps -> {
                ps.setObject(1, utc(lower));
                ps.setObject(2, utc(upper));
                ps.setArray(3, ps.getConnection().createArrayOf("varchar", events));
            }, DetailBuilder::mapRaw);
            return writeRows(raws, built, users, erased, d -> !d.isBefore(from) && !d.isAfter(to));
        });
    }

    /** 重算等实时增量当前那一片提交的最长时间；超时抛错 → 请求置 FAILED。 */
    static final String RECOMPUTE_LOCK_TIMEOUT = "60s";

    /** 在调用方的埋点事务内阻塞拿 {@link AnalyticsLockKeys#DETAIL_BUILDER}（事务级，提交即释放）。 */
    void lockDetailBuilderBlocking() {
        jdbc.execute("SET LOCAL lock_timeout = '" + RECOMPUTE_LOCK_TIMEOUT + "'");
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> null, AnalyticsLockKeys.NAMESPACE,
                AnalyticsLockKeys.DETAIL_BUILDER);
    }

    /** 明细进度游标（{@code pipeline='detail'}）；进度行缺失返回 {@code null}。 */
    Instant progressOrNull() {
        return queryInstant(PROGRESS_SQL, PIPELINE);
    }

    /** {@code (lower, limit]} 内最早的 {@code received_at}，无行为 {@code null}（供 {@link #sliceUpper} 跳过空区间）。 */
    Instant nextReceived(Instant lower, Instant limit) {
        return queryInstant(NEXT_RECEIVED_SQL, utc(lower), utc(limit));
    }

    /**
     * 重算前清空 {@code event_date ∈ [from, to]} 的明细行：一个埋点事务，阻塞拿 {@link AnalyticsLockKeys#DETAIL_BUILDER}
     * （调用方须先把请求置 {@code RUNNING} 并提交，之后拿到锁的实时增量片都会看到 RUNNING 而跳过）。
     * 用 {@code DELETE}（写入账号即表主，权限够；事务内可回滚，不涉及 DDL 锁）。
     *
     * @return 删除的行数
     */
    long clearForRecompute(LocalDate from, LocalDate to) {
        Integer n = tx.execute(status -> {
            lockDetailBuilderBlocking();
            return jdbc.update("DELETE FROM events_detail WHERE event_date BETWEEN ? AND ?", from, to);
        });
        return n == null ? 0 : n;
    }

    /** 测试钩子：写完数据与进度、提交之前调用（集成测试在这里抛错验证同事务回滚）。 */
    protected void beforeCommit() {
    }

    // ---------------------------------------------------------------- 纯转换（L0）

    /**
     * 原始行 → 明细行：时间校正、属性瘦身、上下文解析、{@code user_key} 补全、生产标记。
     *
     * @param allowedKeys 事件 → 清单登记的属性键
     */
    static Mapped toDetail(RawRow raw, Set<LocalDate> built, Function<String, Set<String>> allowedKeys,
            Map<Long, UserAccount> users) {
        return toDetail(raw, built, allowedKeys, users, Erased.NONE, Set.of());
    }

    /**
     * 同上，另做 Story 1.8 注销擦除拦截：{@code user_id} / {@code user_key} 命中 {@code erased} 的行置空身份列并剥掉
     * {@code idKeys} 全部键；其余行里 {@code idKeys} 的值命中 {@code erased.values()} 的键删除。生产标记仍按原 {@code user_id}
     * 判（角色 / 账号类型不是身份信息，结果只是一个布尔）。
     *
     * @param idKeys 清单里 {@code user_identifier: true} 的键
     */
    static Mapped toDetail(RawRow raw, Set<LocalDate> built, Function<String, Set<String>> allowedKeys,
            Map<Long, UserAccount> users, Erased erased, Set<String> idKeys) {
        EventTimeCorrector.Corrected c = EventTimeCorrector.correct(raw.clientTs(), raw.sentAt(), raw.receivedAt(),
                built);
        JsonNode ctx = parseObject(raw.contextJson());
        Verdict verdict = ProductionFlag.judge(ctx, raw.userId(), users);
        boolean erasedUser = erased.matches(raw.userId(), raw.userKey());
        Long userId = erasedUser ? null : raw.userId();
        String userKey = erasedUser ? null : raw.userKey();
        if (userKey == null && userId != null) {
            userKey = AnalyticsDistinctId.of(userId);
        }
        String properties = slimProperties(raw.propertiesJson(), allowedKeys.apply(raw.event()));
        if (!erased.isEmpty()) {
            properties = stripIdentifiers(properties, idKeys, erasedUser, erased.values());
        }
        DetailRow row = new DetailRow(raw.messageId(), raw.event(), raw.clientTs(), c.eventTime(), c.eventDate(),
                c.suspicious(), raw.source(), raw.anonymousId(), userId, userKey,
                text(ctx, "platform"), text(ctx, "appVersion"), text(ctx, "osVersion"), text(ctx, "deviceModel"),
                verdict.isProduction(), properties);
        return new Mapped(row, verdict);
    }

    /** 只保留清单登记的键（保持原顺序）；非对象 / 解析失败按空对象。 */
    static String slimProperties(String propertiesJson, Set<String> allowed) {
        JsonNode node = parseObject(propertiesJson);
        ObjectNode out = JSON.createObjectNode();
        if (node != null && allowed != null && !allowed.isEmpty()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if (allowed.contains(e.getKey())) {
                    out.set(e.getKey(), e.getValue());
                }
            }
        }
        return JSON.writeValueAsString(out);
    }

    /**
     * Story 1.8：剥用户标识键。{@code all} 为真 → 删掉 {@code idKeys} 全部键；否则只删值（字符串 / 数字原样转文本，与 PG
     * {@code ->>} 同口径）落在 {@code values} 里的键。输入是 {@link #slimProperties} 的输出（必为 JSON 对象）。
     */
    static String stripIdentifiers(String propertiesJson, Set<String> idKeys, boolean all, Set<String> values) {
        if (idKeys == null || idKeys.isEmpty()) {
            return propertiesJson;
        }
        JsonNode node = parseObject(propertiesJson);
        if (node == null) {
            return propertiesJson;
        }
        ObjectNode obj = (ObjectNode) node;
        boolean changed = false;
        for (String k : idKeys) {
            JsonNode v = obj.get(k);
            if (v == null) {
                continue;
            }
            if (all || (v.isValueNode() && !v.isNull() && values.contains(v.asString()))) {
                obj.remove(k);
                changed = true;
            }
        }
        return changed ? JSON.writeValueAsString(obj) : propertiesJson;
    }

    /** 上下文里的字符串字段：缺失 / 非字符串 / 空白为 null，超长截断到 {@link #MAX_TEXT}。 */
    static String text(JsonNode ctx, String key) {
        if (ctx == null) {
            return null;
        }
        JsonNode v = ctx.get(key);
        if (v == null || !v.isString()) {
            return null;
        }
        String s = v.stringValue().strip();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    private static JsonNode parseObject(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(json);
            return n != null && n.isObject() ? n : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 库访问

    private Instant readProgress() {
        Instant t = queryInstant(PROGRESS_SQL, PIPELINE);
        if (t == null) {
            throw new IllegalStateException("pipeline_progress 缺少 detail 行");
        }
        return t;
    }

    private Instant queryInstant(String sql, Object... args) {
        return jdbc.query(sql, rs -> {
            if (!rs.next()) {
                return null;
            }
            OffsetDateTime t = rs.getObject(1, OffsetDateTime.class);
            return t == null ? null : t.toInstant();
        }, args);
    }

    /** 本片候选行里在明细层 {@code event_date} 前后各 1 天内已存在的 {@code message_id}。 */
    private Set<String> existingMessageIds(List<DetailRow> candidates) {
        Map<LocalDate, List<String>> byDate = new TreeMap<>();
        for (DetailRow row : candidates) {
            byDate.computeIfAbsent(row.eventDate(), d -> new ArrayList<>()).add(row.messageId());
        }
        Set<String> existing = new HashSet<>();
        for (Map.Entry<LocalDate, List<String>> e : byDate.entrySet()) {
            String[] ids = e.getValue().toArray(String[]::new);
            LocalDate d = e.getKey();
            jdbc.query(EXISTING_SQL, ps -> {
                ps.setObject(1, d.minusDays(1));
                ps.setObject(2, d.plusDays(1));
                ps.setArray(3, ps.getConnection().createArrayOf("varchar", ids));
            }, (ResultSet rs) -> {
                existing.add(rs.getString(1));
            });
        }
        return existing;
    }

    /** Story 1.8：业务库一次窄查询 outbox（本片用户任何状态 + 近期请求）。 */
    private Erased lookupErased(List<Long> sliceUserIds) {
        Long[] arr = sliceUserIds.toArray(Long[]::new);
        OffsetDateTime since = utc(clock.instant().minus(ERASED_VALUE_WINDOW));
        Set<Long> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        Set<String> values = new HashSet<>();
        businessJdbc.query(ERASED_SQL, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("bigint", arr));
            ps.setObject(2, since);
        }, (ResultSet rs) -> {
            long id = rs.getLong(1);
            ids.add(id);
            values.add(Long.toString(id));
            String key = rs.getString(2);
            if (key != null) {
                keys.add(key);
                values.add(key);
            }
            String token = rs.getString(3);
            if (token != null && !token.isEmpty()) {
                values.add(token);
            }
        });
        return ids.isEmpty() ? Erased.NONE : new Erased(Set.copyOf(ids), Set.copyOf(keys), Set.copyOf(values));
    }

    /** 业务库一次窄查询本片用户的角色与账号类型。 */
    private Map<Long, UserAccount> lookupUsers(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Long[] arr = ids.toArray(Long[]::new);
        Map<Long, UserAccount> out = new HashMap<>();
        businessJdbc.query(USERS_SQL, ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", arr)),
                (ResultSet rs) -> {
                    out.put(rs.getLong(1), new UserAccount(rs.getString(2), rs.getString(3)));
                });
        return out;
    }

    private static RawRow mapRaw(ResultSet rs, int rowNum) throws SQLException {
        long uid = rs.getLong(8);
        Long userId = rs.wasNull() ? null : uid;
        return new RawRow(rs.getString(1), rs.getString(2), instant(rs, 3), instant(rs, 4), instant(rs, 5),
                rs.getString(6), rs.getString(7), userId, rs.getString(9), rs.getString(10), rs.getString(11));
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static void bind(PreparedStatement ps, DetailRow r) throws SQLException {
        ps.setString(1, r.messageId());
        ps.setString(2, r.event());
        ps.setObject(3, utc(r.clientTs()));
        ps.setObject(4, utc(r.eventTime()));
        ps.setObject(5, r.eventDate());
        ps.setBoolean(6, r.suspicious());
        ps.setString(7, r.source());
        ps.setString(8, r.anonymousId());
        if (r.userId() == null) {
            ps.setNull(9, Types.BIGINT);
        } else {
            ps.setLong(9, r.userId());
        }
        ps.setString(10, r.userKey());
        ps.setString(11, r.platform());
        ps.setString(12, r.appVersion());
        ps.setString(13, r.osVersion());
        ps.setString(14, r.deviceModel());
        ps.setBoolean(15, r.production());
        ps.setString(16, r.propertiesJson());
    }

    private void alertFailed(String cause, String action) {
        LocalDate day = EventTimeCorrector.wibDate(clock.instant());
        try {
            alerts.send(OpsChannel.TECH, ALERT_FAILED + day, "【埋点明细转换失败】环境 " + appEnv + "\n"
                    + "原因：" + cause + "\n"
                    + "影响：出错的时间片已整片回滚，进度不前进；数据仍在原始层，修好后下一轮自动从断点继续。"
                    + "埋点接收不受影响。当天（WIB）只告警一次。\n"
                    + "处理：" + action);
        } catch (RuntimeException e) {
            log.warn("analytics detail alert failed: {}", e.getClass().getSimpleName());
        }
    }

    private static OffsetDateTime utc(Instant t) {
        return t == null ? null : OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}

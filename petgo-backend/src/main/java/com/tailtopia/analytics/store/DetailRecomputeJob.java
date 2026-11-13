package com.tailtopia.analytics.store;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.store.DetailBuilder.SliceResult;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 明细层重算（analytics-pipeline Story 1.7，D-7 / D-8 / FR-13）：消费埋点库 {@code detail_recompute_requests}，
 * 按 WIB 日期区间从原始层重算明细。入口只有「用写入账号插一行」，不新增 HTTP 接口（架构边界）。
 *
 * <h2>一次请求</h2>
 * <ol>
 *   <li>取最早一条 {@code PENDING}（崩溃遗留的 {@code RUNNING} 优先，从头重跑，幂等）。</li>
 *   <li><b>范围校验</b>：需要的原始层收到时间 = {@code [from 的 WIB 零点 − 1 分钟, to 次日 WIB 零点 + 7 天 + 1 分钟]}，
 *       上界再截到明细进度游标（不越过实时增量，避免两边处理同一批原始行；游标在<b>清空之后</b>重读，此时已冻结）。
 *       最早的原始层分区晚于下界（已被清理）→
 *       {@code REJECTED} / {@code RAW_RETENTION_EXCEEDED} + 告警。</li>
 *   <li>置 {@code RUNNING}（先提交）→ 拿 {@code DETAIL_BUILDER} 锁清空 {@code event_date ∈ [from, to]} 的明细行 →
 *       按 5 分钟时间片（与实时同一个 {@link DetailBuilder#sliceUpper}，空区间直接跳）逐片
 *       {@link DetailBuilder#recomputeSlice}：复用实时的同一套转换 / 查重 / 写入，按<b>当前</b>清单判定事件是否登记，
 *       只写区间内的行，每片一个事务、拿同一把锁 → 置 {@code DONE}。</li>
 *   <li>任何异常 → {@code FAILED}（{@code error_code} 只放异常类名）+ 告警，不自动重试（人工改回 {@code PENDING}）。</li>
 * </ol>
 *
 * <p>{@code RUNNING} 期间 {@link DetailBuilder} 整片跳过（明细新鲜度暂停），{@link PartitionMaintenanceJob} 本轮不删原始层。
 *
 * <p>前提：单实例部署（架构：德国单机）。认领用条件 UPDATE，但不防两个实例同时重跑同一条崩溃遗留的 {@code RUNNING}。
 *
 * <p>日志只打请求 id、日期与条数。告警在埋点事务之外发。埋点库只经 {@code @Qualifier("analytics")} 的 {@link JdbcTemplate}。
 */
@Component
public class DetailRecomputeJob {

    private static final Logger log = LoggerFactory.getLogger(DetailRecomputeJob.class);

    static final String ALERT_REJECTED = "analytics-detail-recompute-rejected:";
    static final String ALERT_FAILED = "analytics-detail-recompute-failed:";
    static final String ERROR_RAW_RETENTION_EXCEEDED = "RAW_RETENTION_EXCEEDED";
    /** 下界余量：校正后时间最多晚于收到时间 5 秒（再晚即可疑，日期回落到收到日），取 1 分钟。 */
    static final Duration RANGE_BEFORE = Duration.ofMinutes(1);
    /** 上界余量：校正后时间最多早于收到时间 7 天（再早即可疑），取 7 天 + 1 分钟。 */
    static final Duration RANGE_AFTER = EventTimeCorrector.PAST_LIMIT.plus(Duration.ofMinutes(1));
    static final int ERROR_CODE_MAX = 64;

    static final String NEXT_SQL = "SELECT id, from_date, to_date FROM detail_recompute_requests "
            + "WHERE status IN ('PENDING', 'RUNNING') ORDER BY (status = 'RUNNING') DESC, id LIMIT 1";
    static final String CLAIM_SQL = "UPDATE detail_recompute_requests SET status = 'RUNNING', started_at = now(), "
            + "done_at = NULL, error_code = NULL WHERE id = ? AND status IN ('PENDING', 'RUNNING')";
    static final String FINISH_SQL = "UPDATE detail_recompute_requests SET status = ?, done_at = now(), error_code = ? "
            + "WHERE id = ?";

    /** 一条请求。 */
    record Request(long id, LocalDate from, LocalDate to) {
    }

    /** 需要读的原始层收到时间区间 {@code (lower, upper]}。 */
    record RawRange(Instant lower, Instant upper) {
    }

    /** 一次运行的结局。 */
    public enum Outcome {
        /** 管道停用（含清单无效：不知道该留哪些事件 / 属性），未运行。 */
        SKIPPED_DISABLED,
        /** 没有待处理的请求。 */
        IDLE,
        /** 一条请求重算完成。 */
        DONE,
        /** 一条请求超出原始层保留期，已拒绝并告警。 */
        REJECTED,
        /** 运行异常（已告警，未向外抛）。 */
        FAILED
    }

    /** 一次运行的汇总（只有 id 与条数）。 */
    public record RunResult(Outcome outcome, Long requestId, int slices, long read, long written, long cleared) {

        static RunResult of(Outcome outcome, Long requestId) {
            return new RunResult(outcome, requestId, 0, 0, 0, 0);
        }
    }

    private final AnalyticsPipelineState state;
    private final JdbcTemplate jdbc;
    private final DetailBuilder builder;
    private final OpsAlertService alerts;
    private final String appEnv;

    @Autowired
    public DetailRecomputeJob(AnalyticsPipelineState state,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            DetailBuilder builder,
            OpsAlertService alerts,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this.state = state;
        this.jdbc = analyticsJdbcTemplate;
        this.builder = builder;
        this.alerts = alerts;
        this.appEnv = appEnv;
    }

    /** 每分钟看一次有没有请求（上一轮结束后再等 {@code fixed-delay-ms}）。 */
    // Story 4.9 AC11：调度由 AnalyticsSchedulingConfig 编程式注册（键名 / 默认值不变，非法值不再让启动失败）
    public void scheduled() {
        runOnce();
    }

    /** 处理至多一条请求：停用跳过，失败告警，绝不抛。 */
    public RunResult runOnce() {
        if (!state.isEnabled()) {
            return RunResult.of(Outcome.SKIPPED_DISABLED, null);
        }
        Request req;
        try {
            req = nextRequest();
        } catch (RuntimeException | Error e) {
            log.warn("analytics detail recompute poll failed: {}", PartitionMaintenanceJob.describe(e));
            return RunResult.of(Outcome.FAILED, null);
        }
        if (req == null) {
            return RunResult.of(Outcome.IDLE, null);
        }
        try {
            return process(req);
        } catch (RuntimeException | Error e) {
            String cause = PartitionMaintenanceJob.describe(e);
            String code = errorCode(e);
            log.warn("analytics detail recompute failed: id={} cause={}", req.id(), cause);
            try {
                jdbc.update(FINISH_SQL, "FAILED", code, req.id());
            } catch (RuntimeException ex) {
                // 置不了 FAILED：请求留在 RUNNING，下一轮从头重跑；期间实时增量继续暂停
                log.warn("analytics detail recompute status update failed: id={} cause={}", req.id(),
                        ex.getClass().getSimpleName());
            }
            alert(ALERT_FAILED + req.id(), "【埋点明细重算失败】环境 " + appEnv + "\n"
                    + "请求 id：" + req.id() + "（event_date " + req.from() + " ~ " + req.to() + "，WIB）\n"
                    + "原因：" + cause + "\n"
                    + "影响：该区间的明细可能不完整（已清空、部分重写）；实时明细转换已恢复。不会自动重试。\n"
                    + "处理：修好原因后用写入账号执行 UPDATE detail_recompute_requests SET status = 'PENDING' WHERE id = "
                    + req.id() + "; 再跑一次（见 runbook「重算明细」）。");
            return RunResult.of(Outcome.FAILED, req.id());
        }
    }

    private RunResult process(Request req) {
        Instant cursor = builder.progressOrNull();
        if (cursor == null) {
            throw new IllegalStateException("pipeline_progress 缺少 detail 行");
        }
        RawRange range = rawRange(req.from(), req.to(), cursor);
        if (!withinRawRetention(PartitionMaintenanceJob.rawBuiltDates(jdbc), range.lower())) {
            jdbc.update(FINISH_SQL, "REJECTED", ERROR_RAW_RETENTION_EXCEEDED, req.id());
            log.warn("analytics detail recompute rejected: id={} from={} to={} reason={}", req.id(), req.from(),
                    req.to(), ERROR_RAW_RETENTION_EXCEEDED);
            alert(ALERT_REJECTED + req.id(), "【埋点明细重算被拒：超出原始层保留期】环境 " + appEnv + "\n"
                    + "请求 id：" + req.id() + "（event_date " + req.from() + " ~ " + req.to() + "，WIB）\n"
                    + "原因：需要从 " + range.lower() + " 起的原始层，但更早的原始分区已按 "
                    + RetentionPolicy.RAW_RETENTION_DAYS + " 天保留期清理。明细未动。\n"
                    + "处理：缩小区间（from_date 往后挪）重新插一行请求。");
            return RunResult.of(Outcome.REJECTED, req.id());
        }
        if (jdbc.update(CLAIM_SQL, req.id()) == 0) {
            return RunResult.of(Outcome.IDLE, null);
        }
        log.info("analytics detail recompute started: id={} from={} to={}", req.id(), req.from(), req.to());
        long cleared = builder.clearForRecompute(req.from(), req.to());
        // 复审修正：上界必须用「清空之后」的游标。认领前读到的游标可能已过时——认领提交前已拿到锁、通过 RUNNING 检查的
        // 实时片会把区间内的行写进明细并推进游标，随后被清空；若仍按旧游标截上界，(旧游标, 新游标] 收到的行两边都不处理 → 丢行。
        // 清空事务在 RUNNING 提交后拿到同一把锁，此后的实时片都会看到 RUNNING 而跳过，所以此刻读到的游标在重算期间冻结。
        Instant frozen = builder.progressOrNull();
        if (frozen == null) {
            throw new IllegalStateException("pipeline_progress 缺少 detail 行");
        }
        RawRange scan = rawRange(req.from(), req.to(), frozen);
        Set<LocalDate> built = DetailPartitions.builtDates(jdbc);
        List<String> registered = builder.registeredEvents();
        int slices = 0;
        long read = 0;
        long written = 0;
        Instant lower = scan.lower();
        while (true) {
            final Instant lo = lower;
            Instant hi = DetailBuilder.sliceUpper(lo, scan.upper(), () -> builder.nextReceived(lo, scan.upper()));
            if (hi == null) {
                break;
            }
            SliceResult s = builder.recomputeSlice(lo, hi, built, registered, req.from(), req.to());
            if (s == null) {
                throw new IllegalStateException("重算时间片未返回结果");
            }
            slices++;
            read += s.read();
            written += s.written();
            lower = hi;
        }
        jdbc.update(FINISH_SQL, "DONE", null, req.id());
        log.info("analytics detail recompute done: id={} from={} to={} slices={} cleared={} read={} written={}",
                req.id(), req.from(), req.to(), slices, cleared, read, written);
        return new RunResult(Outcome.DONE, req.id(), slices, read, written, cleared);
    }

    // ---------------------------------------------------------------- 纯计算（L0）

    /**
     * 需要读的原始层收到时间区间：{@code (from 的 WIB 零点 − 1 分钟, min(to 次日 WIB 零点 + 7 天 + 1 分钟, cursor)]}。
     * 游标落后于下界时上界 = 下界（空区间，只清空不重写——那段明细本就还没生成）。
     */
    static RawRange rawRange(LocalDate from, LocalDate to, Instant cursor) {
        Instant lower = from.atStartOfDay(EventTimeCorrector.WIB).toInstant().minus(RANGE_BEFORE);
        Instant wanted = to.plusDays(1).atStartOfDay(EventTimeCorrector.WIB).toInstant().plus(RANGE_AFTER);
        Instant upper = cursor.isBefore(wanted) ? cursor : wanted;
        if (upper.isBefore(lower)) {
            upper = lower;
        }
        return new RawRange(lower, upper.truncatedTo(ChronoUnit.MICROS));
    }

    /** 最早的原始层日分区（UTC 零点）不晚于下界才算在保留期内；一个分区都没有 → 不在。 */
    static boolean withinRawRetention(Set<LocalDate> rawPartitionUtcDates, Instant lower) {
        if (rawPartitionUtcDates.isEmpty()) {
            return false;
        }
        LocalDate earliest = Collections.min(rawPartitionUtcDates);
        return !earliest.atStartOfDay(ZoneOffset.UTC).toInstant().isAfter(lower);
    }

    /** {@code error_code}：只放异常类名，截到列宽。 */
    static String errorCode(Throwable e) {
        String name = e.getClass().getSimpleName();
        if (name.isEmpty()) {
            name = e.getClass().getName();
        }
        return name.length() > ERROR_CODE_MAX ? name.substring(0, ERROR_CODE_MAX) : name;
    }

    // ---------------------------------------------------------------- 库访问

    private Request nextRequest() {
        List<Request> rows = jdbc.query(NEXT_SQL, (rs, n) -> new Request(rs.getLong(1),
                rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class)));
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private void alert(String dedupeKey, String text) {
        try {
            alerts.send(OpsChannel.TECH, dedupeKey, text);
        } catch (RuntimeException e) {
            log.warn("analytics detail recompute alert failed: {}", e.getClass().getSimpleName());
        }
    }
}

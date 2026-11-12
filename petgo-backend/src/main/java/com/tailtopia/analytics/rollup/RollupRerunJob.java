package com.tailtopia.analytics.rollup;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.rollup.RollupService.RunResult;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 汇总手动重跑（analytics-pipeline Story 4.1 AC4）：消费埋点库 {@code rollup_rerun_requests}，按 WIB 日期区间重跑指定指标族。
 *
 * <p><b>形态与 Story 1.7 的明细重算一致</b>（story T6「若 1.7 入口形态不同，改成与 1.7 同一形态」）：1.7 按架构边界
 * 「只新增 {@code POST /api/v1/analytics/batch} 一个对外端点、异步一律 @Scheduled + DB 状态机」做成请求表 + 定时消费，
 * 不新增 HTTP 接口。本类同理，由 Dai 用写入账号插一行触发：
 * <pre>INSERT INTO rollup_rerun_requests (from_date, to_date, families) VALUES ('2026-11-01', '2026-11-07', '{install}');</pre>
 *
 * <ol>
 *   <li>取最早一条 {@code PENDING}（崩溃遗留的 {@code RUNNING} 优先，从头重跑，幂等）。本 JVM 有汇总在跑 → 本轮不动（等下一轮）。</li>
 *   <li>校验 {@code from ≤ to ≤ asOf}、{@code from ≥ asOf − retention-days}、指标族已注册；不通过 → {@code REJECTED} + 原因码 + 告警。</li>
 *   <li>条件 UPDATE 认领为 {@code RUNNING} → {@link RollupService#rerun} → {@code DONE} / {@code FAILED}（单元失败已由
 *       {@link RollupService} 告警）。被并发挡住（BUSY / LOCKED / 停用）→ 改回 {@code PENDING}，下一轮再试。</li>
 * </ol>
 * 日志只打请求 id、日期与指标族。
 */
@Component
public class RollupRerunJob {

    private static final Logger log = LoggerFactory.getLogger(RollupRerunJob.class);

    static final String ALERT_REJECTED = "analytics-rollup-rerun-rejected:";
    static final int ERROR_CODE_MAX = 200;

    static final String NEXT_SQL = "SELECT id, from_date, to_date, families FROM rollup_rerun_requests "
            + "WHERE status IN ('PENDING', 'RUNNING') ORDER BY (status = 'RUNNING') DESC, id LIMIT 1";
    static final String CLAIM_SQL = "UPDATE rollup_rerun_requests SET status = 'RUNNING', started_at = now(), "
            + "done_at = NULL, error_code = NULL WHERE id = ? AND status IN ('PENDING', 'RUNNING')";
    static final String FINISH_SQL = "UPDATE rollup_rerun_requests SET status = ?, done_at = now(), error_code = ? "
            + "WHERE id = ?";
    static final String RELEASE_SQL = "UPDATE rollup_rerun_requests SET status = 'PENDING', started_at = NULL "
            + "WHERE id = ? AND status = 'RUNNING'";

    /** 一条请求；{@code families} 为空 = 全部。 */
    record Request(long id, LocalDate from, LocalDate to, List<String> families) {
    }

    /** 一次轮询的结局。 */
    public enum Outcome {
        /** 汇总关闭 / 管道停用，未运行。 */
        SKIPPED_DISABLED,
        /** 本 JVM 有汇总在跑，或被并发挡住，请求保持 / 改回 PENDING。 */
        BUSY,
        /** 没有待处理的请求。 */
        IDLE,
        /** 一条请求重跑完成。 */
        DONE,
        /** 一条请求参数不合法，已拒绝。 */
        REJECTED,
        /** 一条请求失败（已告警）。 */
        FAILED
    }

    private final RollupService service;
    private final JdbcTemplate jdbc;
    private final OpsAlertService alerts;
    private final String appEnv;
    private final Clock clock;

    @Autowired
    public RollupRerunJob(RollupService service,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            OpsAlertService alerts,
            @Value("${analytics.app-env:dev}") String appEnv) {
        this(service, analyticsJdbcTemplate, alerts, appEnv, Clock.systemUTC());
    }

    RollupRerunJob(RollupService service, JdbcTemplate jdbc, OpsAlertService alerts, String appEnv, Clock clock) {
        this.service = service;
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.appEnv = appEnv;
        this.clock = clock;
    }

    /** 每分钟看一次有没有请求（上一轮结束后再等 {@code rerun-fixed-delay-ms}）。 */
    @Scheduled(fixedDelayString = "${analytics.rollup.rerun-fixed-delay-ms:60000}",
            initialDelayString = "${analytics.rollup.rerun-initial-delay-ms:60000}")
    public void scheduled() {
        runOnce();
    }

    /** 处理至多一条请求。绝不抛。 */
    public Outcome runOnce() {
        AnalyticsRollupProperties props = service.properties();
        if (!service.isEnabled()) {
            return Outcome.SKIPPED_DISABLED;
        }
        if (service.isRunning()) {
            return Outcome.BUSY;
        }
        Request req;
        try {
            req = next();
        } catch (RuntimeException e) {
            log.warn("analytics rollup rerun poll failed: {}", RollupService.describe(e));
            return Outcome.FAILED;
        }
        if (req == null) {
            return Outcome.IDLE;
        }
        LocalDate asOf = RollupJob.asOf(clock);
        Optional<String> reject = RollupService.validateRerun(req.from(), req.to(), asOf, props.retentionDays(),
                req.families().isEmpty() ? null : req.families(), service.familyNames());
        try {
            if (reject.isPresent()) {
                jdbc.update(FINISH_SQL, "REJECTED", reject.get(), req.id());
                log.warn("analytics rollup rerun rejected: id={} from={} to={} reason={}", req.id(), req.from(),
                        req.to(), reject.get());
                alert(ALERT_REJECTED + req.id(), "【埋点汇总重跑被拒】环境 " + appEnv + "\n"
                        + "请求 id：" + req.id() + "（" + req.from() + " ~ " + req.to() + "，WIB；指标族 "
                        + (req.families().isEmpty() ? "全部" : req.families()) + "）\n"
                        + "原因：" + reject.get() + "（要求 from ≤ to ≤ " + asOf + "，from ≥ "
                        + RollupService.floor(asOf, props.retentionDays()) + "，指标族只能是 " + service.familyNames()
                        + "）\n处理：改正后重新插一行请求。");
                return Outcome.REJECTED;
            }
            if (jdbc.update(CLAIM_SQL, req.id()) == 0) {
                return Outcome.IDLE;
            }
            RunResult r = service.rerun(req.from(), req.to(), req.families(), asOf);
            switch (r.outcome()) {
                case DONE -> {
                    jdbc.update(FINISH_SQL, "DONE", null, req.id());
                    log.info("analytics rollup rerun done: id={} units={}", req.id(), r.units());
                    return Outcome.DONE;
                }
                case FAILED -> {
                    String code = errorCode(r);
                    jdbc.update(FINISH_SQL, "FAILED", code, req.id());
                    log.warn("analytics rollup rerun failed: id={} {}", req.id(), code);
                    return Outcome.FAILED;
                }
                default -> {
                    jdbc.update(RELEASE_SQL, req.id());
                    log.info("analytics rollup rerun deferred: id={} outcome={}", req.id(), r.outcome());
                    return Outcome.BUSY;
                }
            }
        } catch (RuntimeException e) {
            // 状态写不进（埋点库此时不可用）：请求留在原状态，下一轮再处理（RUNNING 会从头重跑，幂等）
            log.warn("analytics rollup rerun status update failed: id={} cause={}", req.id(),
                    RollupService.describe(e));
            return Outcome.FAILED;
        }
    }

    static String errorCode(RunResult r) {
        String s = "failed=" + r.failedFamilies() + " skipped=" + r.skippedFamilies();
        return s.length() > ERROR_CODE_MAX ? s.substring(0, ERROR_CODE_MAX) : s;
    }

    private Request next() {
        List<Request> rows = jdbc.query(NEXT_SQL, (ResultSet rs, int n) -> new Request(rs.getLong(1),
                rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class), families(rs.getArray(4))));
        return rows.isEmpty() ? null : rows.get(0);
    }

    static List<String> families(Array arr) throws SQLException {
        if (arr == null) {
            return List.of();
        }
        Object[] values = (Object[]) arr.getArray();
        return Arrays.stream(values).filter(v -> v != null && !v.toString().isBlank())
                .map(v -> v.toString().strip()).distinct().toList();
    }

    private void alert(String dedupeKey, String text) {
        try {
            alerts.send(OpsChannel.TECH, dedupeKey, text);
        } catch (RuntimeException e) {
            log.warn("analytics rollup rerun alert failed: {}", e.getClass().getSimpleName());
        }
    }
}

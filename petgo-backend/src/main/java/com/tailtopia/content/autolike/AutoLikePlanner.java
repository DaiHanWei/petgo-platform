package com.tailtopia.content.autolike;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.autolike.AutoLikeTurnPicker.OpenTarget;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 自动点赞一轮计划（V1.5.0 Story 2.2，FR-212 / FR-214 / FR-221，架构 L-6 / L-8）：检查 + 关闭 + 排期，
 * <b>整轮一个事务</b>（REQUIRES_NEW），第一句拿 advisory xact 锁（照 {@code TopicHeatJob}）。只写库、不点赞、不发事件。
 *
 * <p>多实例防重三层：advisory 锁（同一时刻只一个实例在排）→ {@code uq_auto_like_runs_slot}（同一槽只排一次）→
 * {@code uq_auto_like_logs_post_slot}（同一帖同一槽最多一行）。
 */
@Component
public class AutoLikePlanner {

    private static final Logger log = LoggerFactory.getLogger(AutoLikePlanner.class);

    static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_xact_lock(hashtext('auto_like_plan'))";

    static final String SLOT_EXISTS_SQL =
            "SELECT EXISTS (SELECT 1 FROM auto_like_runs WHERE run_date = :runDate AND slot = :slot)";

    static final String OVERLAP_SQL =
            "SELECT EXISTS (SELECT 1 FROM auto_like_runs WHERE status = 'ACTIVE' AND window_end > :now)";

    static final String INSERT_RUN_SQL = """
            INSERT INTO auto_like_runs (run_date, slot, started_at, window_end, status, launch)
            VALUES (:runDate, :slot, :now, :windowEnd, 'ACTIVE', false)
            ON CONFLICT ON CONSTRAINT uq_auto_like_runs_slot DO NOTHING
            RETURNING id
            """;

    static final String UPDATE_RUN_CHECKED_SQL = """
            UPDATE auto_like_runs SET launch = :launch, checked_count = :checked, joined_count = :joined,
                   updated_at = now()
            WHERE id = :runId
            """;

    static final String STOP_NO_POOL_SQL = """
            UPDATE auto_like_runs SET status = 'STOPPED', stop_reason = 'NO_POOL', updated_at = now()
            WHERE id = :runId
            """;

    static final String OPEN_TARGETS_SQL = """
            SELECT post_id, author_id, post_created_at, last_liked_at
            FROM auto_like_targets
            WHERE joined AND left_at IS NULL
            """;

    static final String INSERT_LOG_SQL = """
            INSERT INTO auto_like_logs (run_id, post_id, run_date, slot, planned_at, window_end, status)
            VALUES (:runId, :postId, :runDate, :slot, :plannedAt, :windowEnd, 'PLANNED')
            ON CONFLICT ON CONSTRAINT uq_auto_like_logs_post_slot DO NOTHING
            """;

    static final String UPDATE_RUN_PLANNED_SQL =
            "UPDATE auto_like_runs SET planned_count = :planned, updated_at = now() WHERE id = :runId";

    /** 计划结果。{@code skipped != null} = 本次没排（锁 / 重叠 / 本槽已排）。 */
    public record PlanResult(SkipReason skipped, boolean stopped, boolean launch, int checked, int joined, int planned) {

        static PlanResult skipped(SkipReason reason) {
            return new PlanResult(reason, false, false, 0, 0, 0);
        }
    }

    /** 跳过原因。 */
    public enum SkipReason {
        /** 另一实例正在计划（没拿到 advisory 锁）。 */
        LOCKED,
        /** 上一轮窗口还没结束（A-AC1）。 */
        OVERLAP,
        /** 本槽已被排过（A-AC2）。 */
        ALREADY_PLANNED
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AutoLikeCheckStep checkStep;
    private final AutoLikeVirtualPool pool;
    private final AutoLikeProperties props;
    /** 随机偏移（毫秒）：入参为上界（不含），返回 {@code [0, bound)}。可注入固定序列便于测试。 */
    private final LongUnaryOperator jitter;

    /** 🔴 双构造器必须标 {@code @Autowired}，否则 Spring 选不出构造器、启动即崩。 */
    @Autowired
    public AutoLikePlanner(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager,
            AutoLikeCheckStep checkStep, AutoLikeVirtualPool pool, AutoLikeProperties props) {
        this(jdbc, txManager, checkStep, pool, props, bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    AutoLikePlanner(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager,
            AutoLikeCheckStep checkStep, AutoLikeVirtualPool pool, AutoLikeProperties props, LongUnaryOperator jitter) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.checkStep = checkStep;
        this.pool = pool;
        this.props = props;
        this.jitter = jitter;
    }

    /** 排一轮。{@code now} = 本轮开始时刻，所有年龄按它算。 */
    public PlanResult plan(AutoLikeSlot slot, Instant now) {
        long t0 = System.currentTimeMillis();
        PlanResult r = tx.execute(status -> planInTx(slot, now));
        if (r.skipped() == null) {
            log.info("auto like {} planned: launch={} checked={} joined={} planned={} stopped={} tookMs={}",
                    slot, r.launch(), r.checked(), r.joined(), r.planned(), r.stopped(),
                    System.currentTimeMillis() - t0);
        }
        return r;
    }

    private PlanResult planInTx(AutoLikeSlot slot, Instant now) {
        Boolean locked = jdbc.queryForObject(TRY_LOCK_SQL, Map.of(), Boolean.class);
        if (!Boolean.TRUE.equals(locked)) {
            log.warn("auto like {} plan skipped: another instance is planning", slot);
            return PlanResult.skipped(SkipReason.LOCKED);
        }
        LocalDate runDate = LocalDate.ofInstant(now, ScheduleWindow.WIB);
        Timestamp nowTs = Timestamp.from(now);
        if (Boolean.TRUE.equals(jdbc.queryForObject(SLOT_EXISTS_SQL,
                new MapSqlParameterSource("runDate", runDate).addValue("slot", slot.name()), Boolean.class))) {
            log.info("auto like {} run skipped: slot already planned", slot);
            return PlanResult.skipped(SkipReason.ALREADY_PLANNED);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(OVERLAP_SQL, Map.of("now", nowTs), Boolean.class))) {
            log.warn("auto like {} run skipped: previous run still in progress", slot);
            return PlanResult.skipped(SkipReason.OVERLAP);
        }
        Instant windowEnd = now.plus(props.getSpreadWindow());
        Timestamp windowEndTs = Timestamp.from(windowEnd);
        List<Long> ids = jdbc.queryForList(INSERT_RUN_SQL, new MapSqlParameterSource("runDate", runDate)
                .addValue("slot", slot.name()).addValue("now", nowTs).addValue("windowEnd", windowEndTs), Long.class);
        if (ids.isEmpty()) {
            log.info("auto like {} run skipped: slot already planned", slot);
            return PlanResult.skipped(SkipReason.ALREADY_PLANNED);
        }
        long runId = ids.get(0);

        AutoLikeCheckStep.CheckResult check = checkStep.run(now);
        jdbc.update(UPDATE_RUN_CHECKED_SQL, new MapSqlParameterSource("runId", runId)
                .addValue("launch", check.launch()).addValue("checked", check.checked())
                .addValue("joined", check.joined()));

        List<User> virtualPool = pool.load();
        if (virtualPool.isEmpty()) {
            // A-AC26 / Q-3：检查结果照常提交，只是不排期、不关任何帖。
            jdbc.update(STOP_NO_POOL_SQL, Map.of("runId", runId));
            log.warn("auto like {} run stopped: no usable virtual account", slot);
            return new PlanResult(null, true, check.launch(), check.checked(), check.joined(), 0);
        }

        List<OpenTarget> open = jdbc.query(OPEN_TARGETS_SQL, (rs, i) -> new OpenTarget(
                rs.getLong("post_id"), rs.getLong("author_id"),
                rs.getTimestamp("post_created_at").toInstant(),
                rs.getTimestamp("last_liked_at") == null ? null : rs.getTimestamp("last_liked_at").toInstant()));
        List<OpenTarget> chosen = AutoLikeTurnPicker.pick(open, props.getMaxLikesPerAuthorPerRun());
        long spreadMs = props.getSpreadWindow().toMillis();
        int planned = 0;
        for (OpenTarget t : chosen) {
            Instant plannedAt = now.plusMillis(jitter.applyAsLong(spreadMs));
            planned += jdbc.update(INSERT_LOG_SQL, new MapSqlParameterSource("runId", runId)
                    .addValue("postId", t.postId()).addValue("runDate", runDate).addValue("slot", slot.name())
                    .addValue("plannedAt", Timestamp.from(plannedAt)).addValue("windowEnd", windowEndTs));
        }
        jdbc.update(UPDATE_RUN_PLANNED_SQL, Map.of("planned", planned, "runId", runId));
        return new PlanResult(null, false, check.launch(), check.checked(), check.joined(), planned);
    }
}

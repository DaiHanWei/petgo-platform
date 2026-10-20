package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.GrowthAccount;
import com.tailtopia.growth.repository.GrowthAccountRepository;
import com.tailtopia.growth.repository.GrowthRecalcJobRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 改等级表后的全量等级重算（V1.4.0 ① Story 4.1 · AD-12）。只用 {@code @Async} + DB 状态机：
 * {@code PENDING → RUNNING → DONE / FAILED}，每次改状态各自一个短事务。
 *
 * <p>执行体在<b>一个事务</b>内：一条 SQL 选出「按新表算出的等级 ≠ 当前等级」的真实用户，逐个锁行后经
 * {@link GrowthLevelChangeHandler#onLevelTableChanged}（升级写待弹 + 升级通知、升降都发事件，§5.4 连锁只在 handler 一处）。
 * 本类<b>不</b>直接 {@code UPDATE level}——那样 handler 看到的已是新值，不会发事件与通知（AD-12 原文的
 * {@code UPDATE … RETURNING} 由此替代）。任何异常 → 整个事务回滚、等级全部保持旧值，任务置 FAILED（只记异常类名）。
 */
@Service
public class GrowthRecalcService {

    /** 真实、未注销用户里新等级与当前等级不同者（新等级 = 满足 {@code threshold <= lifetime_total} 的最大级）。 */
    static final String AFFECTED_SQL = "SELECT a.user_id FROM growth_accounts a JOIN users u ON u.id = a.user_id "
            + "WHERE u.account_type = 'REAL' AND u.deleted_at IS NULL "
            + "AND COALESCE((SELECT MAX(l.level) FROM growth_levels l WHERE l.threshold <= a.lifetime_total), 1) "
            + "<> a.level ORDER BY a.user_id";

    private static final Logger log = LoggerFactory.getLogger(GrowthRecalcService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final GrowthAccountRepository accounts;
    private final GrowthRecalcJobRepository jobs;
    private final GrowthLevelChangeHandler handler;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public GrowthRecalcService(NamedParameterJdbcTemplate jdbc, GrowthAccountRepository accounts,
            GrowthRecalcJobRepository jobs, GrowthLevelChangeHandler handler, PlatformTransactionManager txm) {
        this(jdbc, accounts, jobs, handler, new TransactionTemplate(txm), Clock.systemUTC());
    }

    GrowthRecalcService(NamedParameterJdbcTemplate jdbc, GrowthAccountRepository accounts,
            GrowthRecalcJobRepository jobs, GrowthLevelChangeHandler handler, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.jobs = jobs;
        this.handler = handler;
        this.tx = tx;
        this.clock = clock;
    }

    /** 候选等级表的一行（预览用，不写库）。 */
    public record LevelRow(int level, long threshold) {
    }

    /** 预览人数：升级 / 降级。 */
    public record Preview(int upgraded, int downgraded) {
    }

    /**
     * 按<b>候选新表</b>预览受影响人数（不写库）：候选表以 {@code VALUES} 绑参进 CTE（不拼值）。
     * 预览与执行结果可能因中间入账略有差异，以执行结果写任务。
     */
    public Preview preview(List<LevelRow> candidate) {
        if (candidate.isEmpty()) {
            return new Preview(0, 0);
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < candidate.size(); i++) {
            values.add("(CAST(:l" + i + " AS INT), CAST(:t" + i + " AS BIGINT))");
            params.addValue("l" + i, candidate.get(i).level());
            params.addValue("t" + i, candidate.get(i).threshold());
        }
        String sql = "WITH lv(level, threshold) AS (VALUES " + String.join(", ", values) + "), "
                + "calc AS (SELECT a.level AS old_level, "
                + "COALESCE((SELECT MAX(lv.level) FROM lv WHERE lv.threshold <= a.lifetime_total), 1) AS new_level "
                + "FROM growth_accounts a JOIN users u ON u.id = a.user_id "
                + "WHERE u.account_type = 'REAL' AND u.deleted_at IS NULL) "
                + "SELECT COUNT(*) FILTER (WHERE new_level > old_level) AS up, "
                + "COUNT(*) FILTER (WHERE new_level < old_level) AS down FROM calc";
        Map<String, Object> row = jdbc.queryForMap(sql, params);
        return new Preview(((Number) row.get("up")).intValue(), ((Number) row.get("down")).intValue());
    }

    /** 保存等级表的事务提交后异步执行（回滚则不触发）。 */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(GrowthRecalcRequestedEvent event) {
        run(event.jobId());
    }

    /** 执行一个任务（同步；只认 PENDING，抢不到状态即返回）。 */
    public void run(long jobId) {
        Integer started = tx.execute(s -> jobs.markRunning(jobId, clock.instant()));
        if (started == null || started == 0) {
            return;
        }
        int[] counts;
        try {
            counts = tx.execute(s -> execute());
        } catch (RuntimeException e) {
            String reason = e.getClass().getSimpleName();
            log.warn("growth recalc failed: jobId={} error={}", jobId, reason);
            tx.execute(s -> jobs.markFailed(jobId, reason, clock.instant()));
            return;
        }
        // 只包执行体：等级已提交后若标记 DONE 失败，任务留在 RUNNING（30 分钟后可重新执行，重算幂等），不误标 FAILED。
        tx.execute(s -> jobs.markDone(jobId, counts[0], counts[1], clock.instant()));
        log.info("growth recalc done: jobId={} upgraded={} downgraded={}", jobId, counts[0], counts[1]);
    }

    /** 一个事务内：选受影响 id → 逐个锁行经 handler 重算。返回 {升级数, 降级数}。 */
    int[] execute() {
        List<Long> ids = jdbc.queryForList(AFFECTED_SQL, Map.of(), Long.class);
        int up = 0;
        int down = 0;
        for (Long id : ids) {
            GrowthAccount a = accounts.lockById(id).orElse(null);
            if (a == null) {
                continue;
            }
            int before = a.getLevel();
            handler.onLevelTableChanged(a);
            int after = a.getLevel();
            if (after > before) {
                up++;
            } else if (after < before) {
                down++;
            }
        }
        return new int[] {up, down};
    }
}

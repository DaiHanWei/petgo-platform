package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 埋点库 {@code agg_progress} 读写（analytics-pipeline Story 4.1，架构 D-5）。只经 {@code @Qualifier("analytics")} 的
 * {@link JdbcTemplate}，不用 JPA。
 *
 * <p>{@link #markDone} 在调用方的单元事务内执行，与写汇总数据同一事务提交（AC3）。
 * {@code last_done_date = GREATEST(COALESCE(旧值, 新值), 新值)}：重算 / 手动重跑旧日期不会让进度倒退。
 */
@Repository
public class RollupProgressRepository {

    /** 一行进度。 */
    public record Progress(String family, LocalDate lastDoneDate, Instant lastRunAt, String lastError) {
    }

    static final int LAST_ERROR_MAX = 200;

    static final String MARK_DONE_SQL = """
            INSERT INTO agg_progress (family, last_done_date, last_run_at, last_error, updated_at)
            VALUES (?, ?, ?, NULL, now())
            ON CONFLICT (family) DO UPDATE SET
              last_done_date = GREATEST(COALESCE(agg_progress.last_done_date, EXCLUDED.last_done_date),
                                        EXCLUDED.last_done_date),
              last_run_at = EXCLUDED.last_run_at,
              last_error = NULL,
              updated_at = now()""";
    static final String RECORD_ERROR_SQL = """
            INSERT INTO agg_progress (family, last_done_date, last_run_at, last_error, updated_at)
            VALUES (?, NULL, ?, ?, now())
            ON CONFLICT (family) DO UPDATE SET
              last_run_at = EXCLUDED.last_run_at,
              last_error = EXCLUDED.last_error,
              updated_at = now()""";
    static final String ALL_SQL = "SELECT family, last_done_date, last_run_at, last_error FROM agg_progress";

    private final JdbcTemplate jdbc;

    public RollupProgressRepository(@Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate) {
        this.jdbc = analyticsJdbcTemplate;
    }

    /** 全部进度，按指标族名索引。 */
    public Map<String, Progress> findAll() {
        Map<String, Progress> out = new HashMap<>();
        List<Progress> rows = jdbc.query(ALL_SQL, (ResultSet rs, int n) -> {
            OffsetDateTime run = rs.getObject(3, OffsetDateTime.class);
            return new Progress(rs.getString(1), rs.getObject(2, LocalDate.class),
                    run == null ? null : run.toInstant(), rs.getString(4));
        });
        for (Progress p : rows) {
            out.put(p.family(), p);
        }
        return out;
    }

    public Optional<Progress> find(String family) {
        return Optional.ofNullable(findAll().get(family));
    }

    /** 推进进度（调用方事务内）：只前进不倒退，清空 {@code last_error}。 */
    public void markDone(String family, LocalDate statDate, Instant runAt) {
        jdbc.update(MARK_DONE_SQL, family, statDate, OffsetDateTime.ofInstant(runAt, ZoneOffset.UTC));
    }

    /** 记录失败（单独一条自动提交语句，在单元事务回滚之后调用）：不动 {@code last_done_date}。 */
    public void recordError(String family, String error, Instant runAt) {
        jdbc.update(RECORD_ERROR_SQL, family, OffsetDateTime.ofInstant(runAt, ZoneOffset.UTC), truncate(error));
    }

    static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > LAST_ERROR_MAX ? error.substring(0, LAST_ERROR_MAX) : error;
    }
}

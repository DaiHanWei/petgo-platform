package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.AnalyticsLockKeys;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 埋点库 {@code ga4_daily_snapshot} 读写（analytics-pipeline Story 4.2）。只经 {@code @Qualifier("analytics")} 的
 * {@link JdbcTemplate}，不用 JPA。
 *
 * <p>🔴 调用方先出网拿到 GA4 结果，再调 {@link #upsert}：每次写入一个独立短事务（锁 + upsert），HTTP 绝不包在事务里。
 */
@Repository
public class Ga4SnapshotRepository {

    static final String LOCK_SQL = "SELECT pg_try_advisory_xact_lock(?, ?)";

    /** 覆盖旧值（GA4 有处理延迟，每次回拉最近 3 天覆盖）。 */
    static final String UPSERT_SQL = """
            INSERT INTO ga4_daily_snapshot (stat_date, active_users_all, active_users_new_version, min_app_version,
                                            fetched_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (stat_date) DO UPDATE SET
              active_users_all = EXCLUDED.active_users_all,
              active_users_new_version = EXCLUDED.active_users_new_version,
              min_app_version = EXCLUDED.min_app_version,
              fetched_at = EXCLUDED.fetched_at""";

    static final String EXISTING_DATES_SQL =
            "SELECT stat_date FROM ga4_daily_snapshot WHERE stat_date BETWEEN ? AND ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    @Autowired
    public Ga4SnapshotRepository(@Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            @Qualifier(AnalyticsDataSourceConfig.QUALIFIER) TransactionTemplate analyticsTransactionTemplate) {
        this.jdbc = analyticsJdbcTemplate;
        this.tx = analyticsTransactionTemplate;
    }

    /** {@code [from, to]} 内已有快照行的日期。 */
    public Set<LocalDate> existingDates(LocalDate from, LocalDate to) {
        return new HashSet<>(jdbc.queryForList(EXISTING_DATES_SQL, LocalDate.class, from, to));
    }

    /**
     * 一个短事务：{@code pg_try_advisory_xact_lock(NAMESPACE, GA4_SNAPSHOT)} → upsert。
     *
     * @return false = 没拿到锁（别的实例正在写），本行未写
     */
    public boolean upsert(LocalDate day, long all, long newVersion, String minAppVersion, Instant fetchedAt) {
        Boolean written = tx.execute(s -> {
            Boolean locked = jdbc.queryForObject(LOCK_SQL, Boolean.class, AnalyticsLockKeys.NAMESPACE,
                    AnalyticsLockKeys.GA4_SNAPSHOT);
            if (!Boolean.TRUE.equals(locked)) {
                return false;
            }
            jdbc.update(UPSERT_SQL, day, all, newVersion, minAppVersion,
                    OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC));
            return true;
        });
        return Boolean.TRUE.equals(written);
    }
}

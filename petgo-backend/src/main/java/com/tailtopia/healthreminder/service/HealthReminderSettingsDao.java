package com.tailtopia.healthreminder.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code health_reminder_settings} 单行（id=1）读写（V1.5.0 Story 2.1，架构增量 §1.3）。上线开关由运维 SQL 打开（无后台页）。
 */
@Repository
public class HealthReminderSettingsDao {

    /** 单行快照。 */
    public record Settings(boolean enabled, Instant switchedOnAt, LocalDate launchDate, boolean queuePaused,
            Instant backfillDoneAt) {
    }

    private final JdbcTemplate jdbc;

    public HealthReminderSettingsDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Settings read() {
        List<Settings> rows = jdbc.query("SELECT enabled, switched_on_at, launch_date, queue_paused, backfill_done_at "
                + "FROM health_reminder_settings WHERE id = 1", (rs, i) -> new Settings(
                        rs.getBoolean("enabled"),
                        toInstant(rs.getTimestamp("switched_on_at")),
                        rs.getObject("launch_date", LocalDate.class),
                        rs.getBoolean("queue_paused"),
                        toInstant(rs.getTimestamp("backfill_done_at"))));
        return rows.isEmpty() ? new Settings(false, null, null, false, null) : rows.get(0);
    }

    public LocalDate launchDate() {
        return read().launchDate();
    }

    /** 首跑写上线日（只写一次：已有值不覆盖）。 */
    public void setLaunchDateIfAbsent(LocalDate day) {
        jdbc.update("UPDATE health_reminder_settings SET launch_date = ?, updated_at = now() "
                + "WHERE id = 1 AND launch_date IS NULL", day);
    }

    public void markBackfillDone(Instant at) {
        jdbc.update("UPDATE health_reminder_settings SET backfill_done_at = ?, updated_at = now() WHERE id = 1",
                Timestamp.from(at));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}

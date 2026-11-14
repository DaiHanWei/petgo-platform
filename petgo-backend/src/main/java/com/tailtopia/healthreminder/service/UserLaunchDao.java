package com.tailtopia.healthreminder.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code health_reminder_user_launch}（每个用户自己的上线日与排队节奏，V1.5.0 Story 2.2，PRD FR-164 个人上线）。 */
@Repository
public class UserLaunchDao {

    /** 一行。 */
    public record UserLaunch(long userId, LocalDate launchDate, LocalDate queueStartDate,
            LocalDate lastQueueSentDate) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public UserLaunchDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserLaunch> find(long userId) {
        List<UserLaunch> rows = jdbc.query("SELECT user_id, launch_date, queue_start_date, last_queue_sent_date "
                + "FROM health_reminder_user_launch WHERE user_id = :u", new MapSqlParameterSource("u", userId),
                (rs, i) -> new UserLaunch(rs.getLong("user_id"), rs.getObject("launch_date", LocalDate.class),
                        rs.getObject("queue_start_date", LocalDate.class),
                        rs.getObject("last_queue_sent_date", LocalDate.class)));
        return rows.stream().findFirst();
    }

    /** 入参里已有上线行的用户。 */
    public Set<Long> usersWithLaunch(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("SELECT user_id FROM health_reminder_user_launch WHERE user_id IN (:ids)",
                new MapSqlParameterSource("ids", userIds), Long.class));
    }

    /** 建行（已有则不动）；@return 是否新建。 */
    public boolean insertIfAbsent(long userId, LocalDate launchDate, LocalDate queueStartDate) {
        return jdbc.update("INSERT INTO health_reminder_user_launch (user_id, launch_date, queue_start_date) "
                + "VALUES (:u, :l, :q) ON CONFLICT (user_id) DO NOTHING", new MapSqlParameterSource("u", userId)
                        .addValue("l", launchDate).addValue("q", queueStartDate)) == 1;
    }

    public void markQueueSent(long userId, LocalDate day) {
        jdbc.update("UPDATE health_reminder_user_launch SET last_queue_sent_date = :d WHERE user_id = :u",
                new MapSqlParameterSource("u", userId).addValue("d", day));
    }
}

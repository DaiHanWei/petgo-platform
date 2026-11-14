package com.tailtopia.profile.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code user_newbie_marks} 的原生读写（V1.5.0 onboarding-recall Story 2.1 · OR-1）。
 *
 * <p>无 JPA 实体（复合主键 + 两列），与 {@code ddl-auto=validate} 无关。
 * 🔴 写入一律 {@code INSERT … ON CONFLICT DO NOTHING}：做过即记、永不撤；并发两次同一任务只一行，
 * 返回值 1 才是「真正新写」（调用方据此决定是否埋点 / 判毕业）。
 */
@Repository
public class UserNewbieMarkDao {

    /** 运行时监听写入。 */
    public static final String SOURCE_RUNTIME = "RUNTIME";

    /** 上线补算写入（Story 2-4）。 */
    public static final String SOURCE_BACKFILL = "BACKFILL";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public UserNewbieMarkDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    /**
     * 记一条（已存在则不动）。由 {@code NewbieMarkService} 的 {@code REQUIRES_NEW} 事务调用。
     *
     * @return 1 = 本次新写入；0 = 已有
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int insertIfAbsent(long userId, String taskKey, String source, Instant occurredAt) {
        return jdbc.update("""
                INSERT INTO user_newbie_marks (user_id, task_key, occurred_at, source)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, task_key) DO NOTHING
                """, userId, taskKey, Timestamp.from(occurredAt), source);
    }

    /** 该账号已记的任务键。 */
    @Transactional(readOnly = true)
    public Set<String> keysOf(long userId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT task_key FROM user_newbie_marks WHERE user_id = ?", String.class, userId));
    }

    /** 批量（一次 IN 查询，Story 6-1 召回分层用）：userId → 已记任务键；没有任何标记的账号不入 map。 */
    @Transactional(readOnly = true)
    public Map<Long, Set<String>> keysOfUsers(Collection<Long> userIds) {
        Map<Long, Set<String>> out = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return out;
        }
        named.query("SELECT user_id, task_key FROM user_newbie_marks WHERE user_id IN (:ids)",
                new MapSqlParameterSource("ids", userIds),
                rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getString(2));
                });
        return out;
    }

    /** 这批账号里已有某个任务键的（NewbieWeekJob 跳过已有 ONE_WEEK 的）。 */
    @Transactional(readOnly = true)
    public Set<Long> usersHaving(Collection<Long> userIds, String taskKey) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = named.queryForList(
                "SELECT user_id FROM user_newbie_marks WHERE task_key = :key AND user_id IN (:ids)",
                new MapSqlParameterSource().addValue("key", taskKey).addValue("ids", userIds), Long.class);
        return new HashSet<>(ids);
    }

    /** 注销清理：删该账号全部行。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public int deleteByUserId(long userId) {
        return jdbc.update("DELETE FROM user_newbie_marks WHERE user_id = ?", userId);
    }
}

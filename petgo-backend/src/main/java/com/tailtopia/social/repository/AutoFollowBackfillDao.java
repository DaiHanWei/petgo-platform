package com.tailtopia.social.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 老用户一次性补关注（V1.4.1，「老用户也默认全部关注」）的整批 SQL。
 *
 * <p>为什么不用 JPA：要在<b>一条语句</b>里插关注行、给每个新关注者 following_count +1、给目标 follower_count
 * 加上新增数，并返回新增数——只能靠 PostgreSQL 的数据修改 CTE；{@code JdbcTemplate} 与 JPA 共用同一事务与连接。
 */
@Repository
public class AutoFollowBackfillDao {

    /**
     * 关注者口径同 {@code AccountQueryService.isActiveRegularUser}（role=USER、REAL、未注销、ACTIVE）；
     * 跳过本人 / 曾主动取关（optout）/ 已拉黑对方 / 已关注。目标本人不会出现在 ins 里，
     * 两个 UPDATE CTE 改的是不同行，不冲突。
     */
    private static final String BACKFILL = """
            WITH ins AS (
                INSERT INTO user_follows (follower_id, followee_id, created_at, source)
                SELECT u.id, ?, now(), 'SYSTEM'
                FROM users u
                WHERE u.role = 'USER' AND u.account_type = 'REAL'
                  AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
                  AND u.id <> ?
                  AND NOT EXISTS (SELECT 1 FROM official_follow_optouts o
                                  WHERE o.user_id = u.id AND o.official_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM user_hide_relations h
                                  WHERE h.holder_id = u.id AND h.target_id = ? AND h.source = 'BLOCK')
                ON CONFLICT (follower_id, followee_id) DO NOTHING
                RETURNING follower_id
            ), followers AS (
                UPDATE users SET following_count = following_count + 1
                WHERE id IN (SELECT follower_id FROM ins)
                RETURNING id
            ), target AS (
                UPDATE users SET follower_count = follower_count + (SELECT count(*) FROM ins)
                WHERE id = ?
                RETURNING id
            )
            SELECT count(*) FROM ins
            """;

    private final JdbcTemplate jdbc;

    public AutoFollowBackfillDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 让所有有效真实用户以 {@code source='SYSTEM'} 关注 {@code targetId}；返回新增关注数。须在事务内调用。 */
    public int backfill(long targetId) {
        Long n = jdbc.queryForObject(BACKFILL, Long.class, targetId, targetId, targetId, targetId, targetId);
        return n == null ? 0 : n.intValue();
    }
}

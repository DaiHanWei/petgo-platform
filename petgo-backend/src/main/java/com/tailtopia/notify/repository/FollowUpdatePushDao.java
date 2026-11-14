package com.tailtopia.notify.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关注更新推送三表的原生读写（V1.5.0 onboarding-recall Story 5.1 · 架构 §3.2 ⑤ · OR-13 / OR-14）：
 * {@code user_post_notify_log}（每条通知一行 + 推送结果）、{@code user_post_push_daily}（同收件人 × 作者 × WIB 日抢位）、
 * {@code follow_update_push_quota}（两类合计每人每 WIB 日名额）。无 JPA 实体。
 *
 * <p>🔴 事务口径：{@link #insertNotifyLog} 是 {@code REQUIRED}，由 {@code UserPostNotifyWriter} 的 {@code REQUIRES_NEW}
 * 事务调用，与写通知同成同败。其余写方法在<b>写通知事务提交之后</b>（{@code afterCommit}）调用 —— 那时原事务资源可能仍
 * 绑在线程上，按 Spring 约定必须 {@code REQUIRES_NEW} 才能真正落库，故一律 {@code REQUIRES_NEW}。
 */
@Repository
public class FollowUpdatePushDao {

    private final JdbcTemplate jdbc;

    public FollowUpdatePushDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记一条关注更新通知（初值 outcome 由调用方给，提交后再 {@link #updateOutcome}）。
     *
     * @return {@code true} = 新插入；{@code false} = 同收件人同帖已登记过（审核补发 / 重复事件）
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean insertNotifyLog(long followerId, long postId, long authorId, LocalDate wibDate, String outcome) {
        int rows = jdbc.update("""
                INSERT INTO user_post_notify_log (follower_id, post_id, author_id, wib_date, push_outcome)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (follower_id, post_id) DO NOTHING
                """, followerId, postId, authorId, wibDate, outcome);
        return rows == 1;
    }

    /** 回写推送结果。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateOutcome(long followerId, long postId, String outcome) {
        jdbc.update("UPDATE user_post_notify_log SET push_outcome = ? WHERE follower_id = ? AND post_id = ?",
                outcome, followerId, postId);
    }

    /** 同收件人 × 同作者 × WIB 日抢推送位：{@code true} = 抢到（今天还没推过这位作者）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimAuthorDaily(long followerId, long authorId, LocalDate wibDate) {
        int rows = jdbc.update("""
                INSERT INTO user_post_push_daily (follower_id, author_id, wib_date)
                VALUES (?, ?, ?)
                ON CONFLICT (follower_id, author_id, wib_date) DO NOTHING
                """, followerId, authorId, wibDate);
        return rows == 1;
    }

    /**
     * 原子占一个当日名额（OR-14）：已用 &lt; cap 时 +1 并返回 {@code true}；满了不改、返回 {@code false}。
     * 单条 UPSERT，并发下行锁串行化，不会超发。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaimQuota(long userId, LocalDate wibDate, int cap) {
        if (cap <= 0) {
            return false;
        }
        List<Integer> used = jdbc.queryForList("""
                INSERT INTO follow_update_push_quota (user_id, wib_date, used) VALUES (?, ?, 1)
                ON CONFLICT (user_id, wib_date) DO UPDATE SET used = follow_update_push_quota.used + 1
                 WHERE follow_update_push_quota.used < ?
                RETURNING used
                """, Integer.class, userId, wibDate, cap);
        return !used.isEmpty();
    }

    /** 删除三表 {@code wib_date < cutoff} 的行（清理任务用）；返回合计删除行数。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteBefore(LocalDate cutoff) {
        return jdbc.update("DELETE FROM user_post_notify_log WHERE wib_date < ?", cutoff)
                + jdbc.update("DELETE FROM user_post_push_daily WHERE wib_date < ?", cutoff)
                + jdbc.update("DELETE FROM follow_update_push_quota WHERE wib_date < ?", cutoff);
    }
}

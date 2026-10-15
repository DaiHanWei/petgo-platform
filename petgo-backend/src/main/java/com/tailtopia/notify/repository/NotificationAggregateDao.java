package com.tailtopia.notify.repository;

import com.tailtopia.notify.domain.NotificationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 互动通知「未读即合并」的原生写入（V1.3.4 follow · Story 3.1 · AD-9）。
 *
 * <p>为什么不用 JPA：合并要在<b>一条 SQL</b> 里原子完成「有未读同键行就复用，否则新建」，靠的是部分唯一索引
 * {@code uq_notifications_unread_aggregate} 上的 {@code ON CONFLICT}；合并时还要刷新 {@code created_at}，
 * 而实体上它是 {@code updatable = false}（不改映射）。{@code JdbcTemplate} 与 JPA 共用同一事务与连接。
 *
 * <p>⚠️ 「先查后插」在并发下会撞唯一索引，Postgres 冲突后当前事务即中止、不能原地重试 —— 所以只用 upsert。
 */
@Repository
public class NotificationAggregateDao {

    /** upsert 结果：行 id、该行的深链 token（合并时是原行的）、是否本次新建。 */
    public record UpsertResult(long id, String deepLinkToken, boolean inserted) {
    }

    private final JdbcTemplate jdbc;

    public NotificationAggregateDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 有未读同键行 → 复用它（不改任何业务列，只为锁行并拿回 id）；否则新建一行。
     *
     * <p>{@code DO UPDATE SET updated_at = notifications.updated_at} 是刻意的空更新：{@code DO NOTHING} 不返回已有行，
     * 而这里必须拿到 id；空更新同时给这一行加了行锁，并发的第二个赞会排在后面、看到同一行。
     * {@code xmax = 0} ⇔ 本次是插入（Postgres 惯用判法）。
     */
    public UpsertResult upsertAggregate(long recipientUserId, NotificationType type, String title, String body,
            String deepLinkType, String deepLinkToken, String targetRef, String aggregateKey, Long commentId,
            String variant) {
        return jdbc.queryForObject("""
                INSERT INTO notifications (recipient_user_id, type, title, body, deep_link_type, deep_link_token,
                                           target_ref, is_read, created_at, updated_at, aggregate_key, comment_id,
                                           variant)
                VALUES (?, ?, ?, ?, ?, ?, ?, false, now(), now(), ?, ?, ?)
                ON CONFLICT (recipient_user_id, aggregate_key) WHERE is_read = false AND aggregate_key IS NOT NULL
                DO UPDATE SET updated_at = notifications.updated_at
                RETURNING id, deep_link_token, (xmax = 0) AS inserted
                """,
                (rs, i) -> new UpsertResult(rs.getLong("id"), rs.getString("deep_link_token"),
                        rs.getBoolean("inserted")),
                recipientUserId, type.name(), title, body, deepLinkType, deepLinkToken, targetRef, aggregateKey,
                commentId, variant);
    }

    /** 记一个触发人；已在这条通知里 → 什么都不做。返回是否新加（T-29：已存在则上层整条跳过）。 */
    public boolean addActor(long notificationId, long actorUserId) {
        return jdbc.update("""
                INSERT INTO notification_actors (notification_id, actor_user_id, acted_at)
                VALUES (?, ?, now())
                ON CONFLICT (notification_id, actor_user_id) DO NOTHING
                """, notificationId, actorUserId) == 1;
    }

    /** 合并后刷新时间：{@code created_at} 也刷，列表按 {@code created_at DESC, id DESC} 排序才会移到最上。 */
    public void touch(long notificationId) {
        jdbc.update("UPDATE notifications SET created_at = now(), updated_at = now() WHERE id = ?", notificationId);
    }

    /**
     * 注销级联（D1/D2）：删掉该用户<b>作为触发人</b>的全部记录（他赞过 / 评论过 / 关注过谁）。
     * 注销是就地匿名化、不删 users 行，{@code actor_user_id} 上的 FK CASCADE 永远不触发，必须显式删。
     * 通知行本身属于收件人，保留；没有触发人的行按存量规则回落旧文案。
     */
    public int deleteActorRowsByUserId(long actorUserId) {
        return jdbc.update("DELETE FROM notification_actors WHERE actor_user_id = ?", actorUserId);
    }
}

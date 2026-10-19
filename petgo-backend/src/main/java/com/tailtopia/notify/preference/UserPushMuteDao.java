package com.tailtopia.notify.preference;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code user_push_mutes} 的原生读写（V1.4.0 ④ Story 1.1 · AD-2）。只存「关闭」的分类，无行 = 开启。
 *
 * <p>为什么不用 JPA：表只有一个复合主键、没有业务字段，关 / 开是 {@code ON CONFLICT DO NOTHING} / {@code DELETE}，
 * 用实体反而要写复合主键类；{@code JdbcTemplate} 与 JPA 共用同一事务与连接。
 */
@Repository
public class UserPushMuteDao {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public UserPushMuteDao(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /** 该用户是否关闭了该分类（走主键索引）。 */
    public boolean isMuted(long userId, PushCategory category) {
        Boolean muted = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_push_mutes WHERE user_id = ? AND category = ?)",
                Boolean.class, userId, category.name());
        return Boolean.TRUE.equals(muted);
    }

    /** 给定用户里关闭了该分类的那些（走 {@code (category, user_id)} 索引）。 */
    public Set<Long> mutedAmong(Collection<Long> userIds, PushCategory category) {
        List<Long> ids = named.queryForList(
                "SELECT user_id FROM user_push_mutes WHERE category = :c AND user_id IN (:ids)",
                Map.of("c", category.name(), "ids", userIds), Long.class);
        return new HashSet<>(ids);
    }

    /** 该用户全部关闭行的分类原始字符串（调用方负责解析，库里理论上只有 CHECK 允许的 8 个值）。 */
    public List<String> mutedCategories(long userId) {
        return jdbc.queryForList("SELECT category FROM user_push_mutes WHERE user_id = ?", String.class, userId);
    }

    /** 关闭：主键冲突即已关闭，幂等（不「先查后插」，并发下不会撞唯一键中止事务）。 */
    public void mute(long userId, PushCategory category) {
        jdbc.update("INSERT INTO user_push_mutes (user_id, category) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userId, category.name());
    }

    /** 开启：删行，0 行也不报错。 */
    public void unmute(long userId, PushCategory category) {
        jdbc.update("DELETE FROM user_push_mutes WHERE user_id = ? AND category = ?", userId, category.name());
    }

    /** 注销：删该用户全部行，幂等。 */
    public int deleteByUserId(long userId) {
        return jdbc.update("DELETE FROM user_push_mutes WHERE user_id = ?", userId);
    }
}

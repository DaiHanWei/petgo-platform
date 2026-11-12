package com.tailtopia.analytics.identity;

import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 埋点库 {@code identity_links} 访问（analytics-pipeline Story 1.5）。只经 {@code @Qualifier("analytics")} 的
 * {@link JdbcTemplate}，禁止 JPA。只追加：本类不提供 UPDATE / DELETE（删除只来自 Story 1.8 注销擦除）。
 */
@Repository
public class IdentityLinkRepository {

    static final String FIND_SQL =
            "SELECT user_id FROM identity_links WHERE anonymous_id = ? AND user_key = ? LIMIT 1";

    /** 无冲突目标的 {@code ON CONFLICT DO NOTHING}：主键冲突与 {@code message_id} 唯一冲突都吞掉，保证重试幂等。 */
    static final String INSERT_SQL = "INSERT INTO identity_links (anonymous_id, user_id, user_key, linked_at, message_id) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbc;

    public IdentityLinkRepository(@Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate) {
        this.jdbc = analyticsJdbcTemplate;
    }

    /** （匿名 id，用户标识）之前是否建立过关联；有则返回关联里的内部用户 id。 */
    public Optional<Long> findUserId(String anonymousId, String userKey) {
        return jdbc.query(FIND_SQL, rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.<Long>empty(),
                anonymousId, userKey);
    }

    /**
     * 批量追加关联（调用方负责事务：{@code IngestFlusher} 在写 {@code events_raw} 的同一个埋点事务里调用）。
     *
     * @return 每行影响条数（冲突被吞的为 0）；空入参返回空数组、不访问库
     */
    public int[] appendAll(List<IdentityLink> links) {
        if (links.isEmpty()) {
            return new int[0];
        }
        return jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                bind(ps, links.get(i));
            }

            @Override
            public int getBatchSize() {
                return links.size();
            }
        });
    }

    static void bind(PreparedStatement ps, IdentityLink l) throws SQLException {
        ps.setString(1, l.anonymousId());
        ps.setLong(2, l.userId());
        ps.setString(3, l.userKey());
        ps.setObject(4, OffsetDateTime.ofInstant(l.linkedAt(), ZoneOffset.UTC));
        ps.setString(5, l.messageId());
    }
}

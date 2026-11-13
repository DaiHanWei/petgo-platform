package com.tailtopia.analytics.identity;

import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 埋点库 {@code identity_links} 访问（analytics-pipeline Story 1.5）。只经 {@code @Qualifier("analytics")} 的
 * {@link JdbcTemplate}，禁止 JPA。只追加：本类不提供 UPDATE / DELETE（删除只来自 Story 1.8 注销擦除）。
 *
 * <p>🔴 <b>两个模板</b>（Story 4.9 AC4 / AC5）：{@link #findUserId} 在 HTTP 请求线程上同步执行，走
 * {@code @Qualifier("analyticsRequest")}（带 {@code analytics.datasource.request-query-timeout} 客户端超时，默认 2s），埋点库卡住时
 * 抛 {@code QueryTimeoutException}，由 {@code LinkTableIdentityResolver} 按「查不到」处理；{@link #appendAll} 只在写库线程
 * （{@code IngestFlusher} 的埋点事务）里调用，继续用不带客户端超时的 {@code @Qualifier("analytics")}。
 */
@Repository
public class IdentityLinkRepository {

    static final String FIND_SQL =
            "SELECT user_id FROM identity_links WHERE anonymous_id = ? AND user_key = ? LIMIT 1";

    /** 无冲突目标的 {@code ON CONFLICT DO NOTHING}：主键冲突与 {@code message_id} 唯一冲突都吞掉，保证重试幂等。 */
    static final String INSERT_SQL = "INSERT INTO identity_links (anonymous_id, user_id, user_key, linked_at, message_id) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbc;
    private final JdbcTemplate requestJdbc;

    @Autowired
    public IdentityLinkRepository(@Qualifier(AnalyticsDataSourceConfig.QUALIFIER) JdbcTemplate analyticsJdbcTemplate,
            @Qualifier(AnalyticsDataSourceConfig.REQUEST_QUALIFIER) JdbcTemplate analyticsRequestJdbcTemplate) {
        this.jdbc = analyticsJdbcTemplate;
        this.requestJdbc = analyticsRequestJdbcTemplate;
    }

    /** 测试 / 写库线程内构造用：查询与追加用同一个模板。 */
    public IdentityLinkRepository(JdbcTemplate jdbc) {
        this(jdbc, jdbc);
    }

    /**
     * （匿名 id，用户标识）之前是否建立过关联；有则返回关联里的内部用户 id。请求线程调用，走带查询超时的模板；
     * 超时 / 库不可用抛 {@code DataAccessException}（调用方按查不到处理）。
     */
    public Optional<Long> findUserId(String anonymousId, String userKey) {
        return requestJdbc.query(FIND_SQL, rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.<Long>empty(),
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

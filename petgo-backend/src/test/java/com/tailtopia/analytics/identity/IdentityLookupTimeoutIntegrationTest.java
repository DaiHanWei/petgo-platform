package com.tailtopia.analytics.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.auth.domain.User;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：请求线程查询超时（analytics-pipeline Story 4.9 AC7）—— 埋点库「卡住」时 {@code POST /api/v1/analytics/batch} 在约 2 秒内
 * 返回 202，需要查关联表的事件按「查不到」剥离身份并标记 {@code identity_unverified}。
 *
 * <p>模拟慢查询：另开一条埋点库连接，在未提交事务里 {@code LOCK TABLE identity_links IN ACCESS EXCLUSIVE MODE}，请求线程的
 * {@code SELECT … FROM identity_links} 会一直等锁（等价于 {@code pg_sleep}，且不需要测试专用 SQL 钩子），直到
 * {@code analytics.datasource.request-query-timeout}（默认 2s）由 JDBC 取消。前置同 {@link IdentityLinksIntegrationTest}。
 */
class IdentityLookupTimeoutIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private DataSource analyticsDataSource;

    private String prefix;
    private String aid;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        prefix = "it-ident-timeout-" + SEQ.incrementAndGet() + "-";
        aid = UUID.randomUUID().toString();
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM events_raw WHERE message_id LIKE ?", prefix + "%");
    }

    @Test
    void slowLinkLookupReturns202WithinTimeoutAndMarksUnverified() throws Exception {
        User a = newUser();
        String keyA = AnalyticsDistinctId.of(a.getId());
        String now = Instant.now().toString();
        // 不带 JWT 但声明了用户标识 → 必须查关联表（A7）
        String body = "{\"batch\":[{\"messageId\":\"" + prefix + "slow\",\"event\":\"app_open\",\"timestamp\":\"" + now
                + "\",\"sentAt\":\"" + now + "\",\"anonymousId\":\"" + aid + "\",\"userId\":\"" + keyA + "\"}]}";

        long elapsedMs;
        try (Connection locker = analyticsDataSource.getConnection()) {
            locker.setAutoCommit(false);
            try (Statement st = locker.createStatement()) {
                st.execute("LOCK TABLE identity_links IN ACCESS EXCLUSIVE MODE");
            }
            long t0 = System.nanoTime();
            mvc.perform(post("/api/v1/analytics/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(1));
            elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            locker.rollback();
        }
        assertThat(elapsedMs).as("请求应在约 2 秒（查询超时）内返回").isBetween(1_500L, 4_500L);

        Map<String, Object> row = awaitEvent(prefix + "slow");
        assertThat(row.get("identity_unverified")).isEqualTo(true);
        assertThat(row.get("user_key")).isNull();
        assertThat(row.get("user_id")).isNull();
    }

    private Map<String, Object> awaitEvent(String messageId) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        List<Map<String, Object>> rows;
        do {
            rows = analyticsJdbc.queryForList("SELECT * FROM events_raw WHERE message_id = ?", messageId);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("事件未落库：" + messageId);
    }
}

package com.tailtopia.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：埋点批量接收端到端（analytics-pipeline Story 1.4 AC1 / AC4 / AC7 / AC10）——真安全链 + 真 Redis 限流 + flusher 真写
 * 埋点测试库 {@code petgo_analytics_test}。
 *
 * <ul>
 *   <li>前置：本地 {@code createdb petgo_analytics_test}（CI 由 backend-ci.yml 建），Redis 可用。</li>
 *   <li>限流 / 队列满 / 管道停用需要改配置或改不可逆状态，放在 {@code AnalyticsBatchLimitsIntegrationTest}（独立上下文）。</li>
 *   <li>告警：手工 new 一个带 mock {@link OpsAlertService} 的 {@link UnknownEventAlertJob}（等价于 story 写的
 *       {@code @MockitoBean}，但不额外起 Spring 上下文 / 连接池，见 {@link ApiIntegrationTest} 类注释，同 1.3 的做法）。</li>
 *   <li>隔离：每个用例用唯一 {@code messageId} 前缀与随机 {@code anonymousId}，结束按前缀删除，不 TRUNCATE。</li>
 * </ul>
 */
class AnalyticsBatchIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private TransactionTemplate analyticsTx;

    private String prefix;
    private String aid;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        prefix = "it-batch-" + SEQ.incrementAndGet() + "-";
        aid = UUID.randomUUID().toString();
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM events_raw WHERE message_id LIKE ?", prefix + "%");
    }

    private String event(String id, String eventName, String extra) {
        return "{\"messageId\":\"" + prefix + id + "\",\"event\":\"" + eventName + "\","
                + "\"timestamp\":\"2026-11-13T08:15:30.123+07:00\",\"sentAt\":\"2026-11-13T08:16:00+07:00\","
                + "\"anonymousId\":\"" + aid + "\"" + extra + "}";
    }

    /** 清单里一定登记了的事件名（取清单第一个），避免硬编码。 */
    @Autowired
    private EventCatalog catalog;

    private String knownEvent() {
        return catalog.events().get(0).name();
    }

    private ResultActions send(String body, String bearer) throws Exception {
        MockHttpServletRequestBuilder req = post(AnalyticsBatchController.PATH)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) {
            req.header("Authorization", bearer);
        }
        return mvc.perform(req);
    }

    private List<Map<String, Object>> awaitRows(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        List<Map<String, Object>> rows;
        do {
            rows = analyticsJdbc.queryForList(
                    "SELECT * FROM events_raw WHERE message_id LIKE ? ORDER BY message_id", prefix + "%");
            if (rows.size() >= expected) {
                return rows;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return rows;
    }

    @Test
    void anonymousBatchAcceptedAndFlushed() throws Exception {
        Instant before = Instant.now();
        String bad = "{\"event\":\"" + knownEvent() + "\",\"timestamp\":\"2026-11-13T08:15:30Z\","
                + "\"sentAt\":\"2026-11-13T08:15:30Z\",\"anonymousId\":\"" + aid + "\"}";

        send("{\"batch\":[" + event("1", knownEvent(), "") + "," + event("2", knownEvent(), "") + "," + bad + ","
                + event("3", knownEvent(), ",\"properties\":{\"sku_id\":\"s-1\"}") + "]}", null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(3))
                .andExpect(jsonPath("$.rejected").value(1));

        List<Map<String, Object>> rows = awaitRows(3);
        Instant after = Instant.now();
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("source")).isEqualTo("APP");
            assertThat(r.get("anonymous_id")).isEqualTo(aid);
            assertThat(r.get("unknown_event")).isEqualTo(false);
            assertThat(r.get("identity_unverified")).isEqualTo(false);
            Instant received = ((Timestamp) r.get("received_at")).toInstant();
            assertThat(received).isBetween(before.minusSeconds(1), after);
            assertThat(((Timestamp) r.get("client_ts")).toInstant())
                    .isEqualTo(Instant.parse("2026-11-13T01:15:30.123Z"));
        });
        assertThat(rows.get(2).get("properties").toString()).contains("sku_id");
    }

    @Test
    void withJwtAccepted() throws Exception {
        User u = newUser();

        send("{\"batch\":[" + event("1", knownEvent(), "") + "]}", userBearer(u.getId()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1));

        assertThat(awaitRows(1)).hasSize(1);
    }

    @Test
    void invalidJwtIs401() throws Exception {
        send("{\"batch\":[" + event("1", knownEvent(), "") + "]}", "Bearer not-a-jwt")
                .andExpect(status().isUnauthorized());
    }

    /** Story 1.5 起：无 JWT、关联表里查不到的用户标识被剥离并标记（原 1.4 直通实现是保留标识 + 标记）。 */
    @Test
    void userIdWithoutLinkStrippedAndFlagged() throws Exception {
        String userKey = "a".repeat(64);

        send("{\"batch\":[" + event("1", knownEvent(), ",\"userId\":\"" + userKey + "\"") + "]}", null)
                .andExpect(status().isAccepted());

        Map<String, Object> row = awaitRows(1).get(0);
        assertThat(row.get("user_key")).isNull();
        assertThat(row.get("user_id")).isNull();
        assertThat(row.get("identity_unverified")).isEqualTo(true);
    }

    @Test
    void unknownEventAcceptedAndAlerted() throws Exception {
        String unknown = "it_unknown_" + SEQ.incrementAndGet();

        send("{\"batch\":[" + event("1", unknown, "") + "," + event("2", unknown, "") + "]}", null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(2));

        List<Map<String, Object>> rows = awaitRows(2);
        assertThat(rows).hasSize(2).allSatisfy(r -> assertThat(r.get("unknown_event")).isEqualTo(true));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        OpsAlertService alerts = mock(OpsAlertService.class);
        UnknownEventAlertJob job = new UnknownEventAlertJob(state, analyticsJdbc, analyticsTx, alerts, "test",
                Clock.systemUTC());

        assertThat(job.run(today)).isEqualTo(UnknownEventAlertJob.Outcome.ALERTED);
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), eq(UnknownEventAlertJob.ALERT_KEY + today),
                contains(unknown + "：2"));
    }
}

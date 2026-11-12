package com.tailtopia.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.ingest.IngestCircuitBreaker.Outcome;
import com.tailtopia.shared.analytics.SelfHostedAnalyticsClient;
import com.tailtopia.support.ApiIntegrationTest;
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

/**
 * L1：停写熔断接入（analytics-pipeline Story 1.10 AC3）——真埋点测试库 {@code petgo_analytics_test} 上的
 * {@code pg_database_size} + 真接收端 + 真 {@link SelfHostedAnalyticsClient}。
 *
 * <ul>
 *   <li>用的是上下文里那个熔断 bean（接收端与服务端事件读的就是它）。基类把定时检查首延拉到 1 天，用例直接调
 *       {@link IngestCircuitBreaker#check(long)}：传 1 字节 = 必然熔断；传 {@link Long#MAX_VALUE} = 必然恢复。</li>
 *   <li>🔴 熔断状态是上下文共享的：{@link #restore()} 无论成败都把它关回去，免得连累同一上下文里后续的埋点测试。</li>
 *   <li>状态切换时 bean 用的是真 {@code OpsAlertService}（测试环境未配飞书群，只 WARN）；告警次数由 L0 {@code IngestCircuitBreakerTest} 覆盖。</li>
 * </ul>
 */
class IngestCircuitBreakerIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    private IngestCircuitBreaker breaker;
    @Autowired
    private SelfHostedAnalyticsClient serverClient;
    @Autowired
    private EventCatalog catalog;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;

    private String prefix;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        breaker.check(Long.MAX_VALUE);
        assertThat(breaker.isOpen()).isFalse();
        prefix = "it-breaker-" + SEQ.incrementAndGet() + "-";
    }

    @AfterEach
    void restore() {
        breaker.check(Long.MAX_VALUE);
        analyticsJdbc.update("DELETE FROM events_raw WHERE message_id LIKE ?", prefix + "%");
    }

    private ResultActions send(String id) throws Exception {
        String body = "{\"batch\":[{\"messageId\":\"" + prefix + id + "\",\"event\":\""
                + catalog.events().get(0).name() + "\",\"timestamp\":\"2026-11-13T08:15:30.123+07:00\","
                + "\"sentAt\":\"2026-11-13T08:16:00+07:00\",\"anonymousId\":\"" + UUID.randomUUID() + "\"}]}";
        return mvc.perform(post(AnalyticsBatchController.PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void stopBytesExceededRejectsIngestThenRecovers() throws Exception {
        assertThat(breaker.check(1)).isEqualTo(Outcome.OPENED);
        assertThat(breaker.isOpen()).isTrue();

        send("1").andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "300"));

        long droppedBefore = serverClient.droppedCount();
        serverClient.captureForUser(42L, "shop_payment_paid", Map.of("pay_channel", "QRIS"));
        assertThat(serverClient.droppedCount()).isEqualTo(droppedBefore + 1);

        // 仍超线：保持 OPEN
        assertThat(breaker.check(1)).isEqualTo(Outcome.UNCHANGED);

        assertThat(breaker.check(Long.MAX_VALUE)).isEqualTo(Outcome.CLOSED);
        send("2").andExpect(status().isAccepted());
    }
}

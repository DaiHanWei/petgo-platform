package com.tailtopia.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1：埋点接收的 429 / 503（analytics-pipeline Story 1.4 AC5 / AC6），真 Redis 限流 + 真安全链。
 *
 * <p>阈值要配小，与 {@link AnalyticsBatchIntegrationTest} 配置不同 → 必然是独立上下文，所以集中在这一个类里
 * （每多一份属性差异就多一个上下文 / 连接池，见 {@link ApiIntegrationTest} 类注释）：
 * <ul>
 *   <li>{@code queue-max-bytes=1}：任何非空批次都放不进队列 → 429（队列满，{@code Retry-After}=7）；</li>
 *   <li>{@code per-device=2}：限流判定在入队之前，同一 {@code anonymousId} 第 3 次起 → 429（限流，{@code Retry-After} ≤ 窗口）。</li>
 * </ul>
 * 两种 429 用 {@code detail} 区分。{@link AnalyticsPipelineState#disable} 不可逆，放最后一个用例，并在其后丢弃上下文。
 */
@TestPropertySource(properties = {
        "analytics.ingest.queue-max-bytes=1",
        "analytics.ingest.queue-full-retry-after-seconds=7",
        "analytics.ingest.rate-limit.per-device=2",
        "analytics.ingest.rate-limit.per-ip=1000000",
        "analytics.ingest.rate-limit.window-seconds=60",
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalyticsBatchLimitsIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    private EventCatalog catalog;

    private ResultActions send(String aid) throws Exception {
        String body = "{\"batch\":[{\"messageId\":\"it-limits-" + SEQ.incrementAndGet() + "\",\"event\":\""
                + catalog.events().get(0).name() + "\",\"timestamp\":\"2026-11-13T08:15:30Z\","
                + "\"sentAt\":\"2026-11-13T08:15:30Z\",\"anonymousId\":\"" + aid + "\"}]}";
        return mvc.perform(post(AnalyticsBatchController.PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @Order(1)
    void queueFullIs429WithRetryAfter() throws Exception {
        assertThat(state.isEnabled()).as("原因=%s", state.reason()).isTrue();

        send(UUID.randomUUID().toString())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(jsonPath("$.type").value("https://petgo/errors/rate-limited"))
                .andExpect(jsonPath("$.detail").value("埋点队列已满，请稍后再试"));
    }

    @Test
    @Order(2)
    void sameAnonymousIdOverLimitIs429WithRetryAfter() throws Exception {
        String aid = UUID.randomUUID().toString();
        send(aid).andExpect(jsonPath("$.detail").value("埋点队列已满，请稍后再试"));
        send(aid).andExpect(jsonPath("$.detail").value("埋点队列已满，请稍后再试"));

        ResultActions third = send(aid)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value("上报过于频繁，请稍后再试"));
        int retryAfter = Integer.parseInt(third.andReturn().getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1, 60);

        // 换一个匿名 id 不受影响（回到队列满）
        send(UUID.randomUUID().toString()).andExpect(jsonPath("$.detail").value("埋点队列已满，请稍后再试"));
    }

    @Test
    @Order(3)
    @DirtiesContext
    void pipelineDisabledIs503() throws Exception {
        state.disable(Reason.DB_UNAVAILABLE, "it");

        send(UUID.randomUUID().toString())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(503));
    }
}

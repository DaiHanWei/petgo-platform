package com.tailtopia.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * L1：优雅停机先停接收、写完队列（analytics-pipeline Story 1.4 AC8）。入队 1,200 条（多于一批 500）后直接调用
 * {@link IngestFlusher#stop()}，断言全部在库；停机中再请求返回 503。
 *
 * <p>停掉 flusher 的上下文不能再给别的测试类复用 → {@link DirtiesContext}：本类跑完即丢弃该上下文。
 * 与其他 {@link ApiIntegrationTest} 子类同配置，开跑前复用缓存上下文，不多占连接池。
 */
@DirtiesContext
class IngestFlusherShutdownIntegrationTest extends ApiIntegrationTest {

    private static final int N = 1_200;

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    private IngestQueue queue;
    @Autowired
    private IngestFlusher flusher;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;

    private final String prefix = "it-shutdown-" + SEQ.incrementAndGet() + "-";

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM events_raw WHERE message_id LIKE ?", prefix + "%");
    }

    @Test
    void stopWritesEverythingQueuedThenRejectsWith503() throws Exception {
        assertThat(state.isEnabled()).as("原因=%s", state.reason()).isTrue();
        Instant now = Instant.now();
        String aid = UUID.randomUUID().toString();
        List<RawEvent> events = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            events.add(RawEvent.of(prefix + i, "it_shutdown_probe", now, now, now, aid, null, null,
                    RawEvent.Source.APP, null, null, false, true));
        }
        assertThat(queue.offerAll(events)).isTrue();

        flusher.stop();

        Long count = analyticsJdbc.queryForObject("SELECT count(*) FROM events_raw WHERE message_id LIKE ?",
                Long.class, prefix + "%");
        assertThat(count).isEqualTo(N);
        assertThat(flusher.isRunning()).isFalse();
        assertThat(queue.size()).isZero();

        String body = "{\"batch\":[{\"messageId\":\"" + prefix + "late\",\"event\":\"x\","
                + "\"timestamp\":\"2026-11-13T08:15:30Z\",\"sentAt\":\"2026-11-13T08:15:30Z\","
                + "\"anonymousId\":\"" + aid + "\"}]}";
        mvc.perform(post(AnalyticsBatchController.PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));
    }
}

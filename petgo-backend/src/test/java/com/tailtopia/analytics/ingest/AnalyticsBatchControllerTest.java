package com.tailtopia.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.identity.IdentityResolver;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.data.redis.RedisConnectionFailureException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

/**
 * L0：analytics-pipeline Story 1.4 AC2 / AC3 / AC5 / AC6 的 controller 层判定（standalone MockMvc，无 Spring 上下文）。
 * 真实安全链 / 落库见 {@code AnalyticsBatchIntegrationTest}（L1）。
 */
class AnalyticsBatchControllerTest {

    private static final String PATH = AnalyticsBatchController.PATH;
    private static final String AID = "3f2c8a1e-9b4d-4c1a-8e7f-0a1b2c3d4e5f";
    private static final Instant NOW = Instant.parse("2026-11-13T01:00:00Z");

    private IngestQueue queue;
    private EventCatalog catalog;
    private AnalyticsPipelineState state;
    private RedisRateLimiter limiter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queue = new IngestQueue(64L * 1024 * 1024);
        catalog = mock(EventCatalog.class);
        when(catalog.isRegistered("known_event")).thenReturn(true);
        state = new AnalyticsPipelineState();
        limiter = mock(RedisRateLimiter.class);
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
        mvc = build(IngestProperties.defaults());
    }

    /**
     * 测试替身（= Story 1.4 的直通语义）：有用户标识 → 原样保留并标 {@code identityUnverified}；没有 → 三项空 / false。
     * 本类只测接口流程，真正的身份判定（Story 1.5 {@code LinkTableIdentityResolver}）另见 {@code IdentityResolverTest}。
     */
    private static final IdentityResolver PASSTHROUGH = (events, jwt) -> events.stream()
            .map(e -> e.userKey() == null ? e.withIdentity(null, null, false) : e.withIdentity(e.userKey(), null, true))
            .toList();

    private MockMvc build(IngestProperties props) {
        AnalyticsBatchController c = new AnalyticsBatchController(queue, catalog, PASSTHROUGH,
                state, limiter, props, Clock.fixed(NOW, ZoneOffset.UTC));
        return MockMvcBuilders.standaloneSetup(c)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private static String event(String messageId, String extra) {
        return "{\"messageId\":\"" + messageId + "\",\"event\":\"known_event\","
                + "\"timestamp\":\"2026-11-13T08:15:30.123+07:00\",\"sentAt\":\"2026-11-13T08:16:00+07:00\","
                + "\"anonymousId\":\"" + AID + "\"" + extra + "}";
    }

    private static String batchOf(String... events) {
        return "{\"batch\":[" + String.join(",", events) + "]}";
    }

    private ResultActions postBody(String body) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void assertProblem(ResultActions r, int status) throws Exception {
        r.andExpect(status().is(status))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("3 条合法 + 1 条缺 messageId → 202 {accepted:3, rejected:1}，入队 3 条，received_at 同批同值")
    void acceptsAndCountsRejected() throws Exception {
        String bad = "{\"event\":\"known_event\",\"timestamp\":\"2026-11-13T08:15:30Z\","
                + "\"sentAt\":\"2026-11-13T08:15:30Z\",\"anonymousId\":\"" + AID + "\"}";

        postBody(batchOf(event("a", ""), event("b", ""), bad, event("c", ",\"userId\":\"uk\"")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(3))
                .andExpect(jsonPath("$.rejected").value(1));

        List<RawEvent> queued = queue.drain(10, Duration.ZERO);
        assertThat(queued).extracting(RawEvent::messageId).containsExactly("a", "b", "c");
        assertThat(queued).allSatisfy(e -> {
            assertThat(e.receivedAt()).isEqualTo(NOW);
            assertThat(e.source()).isEqualTo(RawEvent.Source.APP);
        });
        RawEvent withUser = queued.get(2);
        assertThat(withUser.userKey()).isEqualTo("uk");
        assertThat(withUser.userId()).isNull();
        assertThat(withUser.identityUnverified()).isTrue();
        assertThat(queued.get(0).identityUnverified()).isFalse();
    }

    @Test
    @DisplayName("事件名不在清单：照收、unknownEvent=true")
    void unknownEventAccepted() throws Exception {
        postBody(batchOf(event("a", "").replace("known_event", "mystery_event")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1));

        assertThat(queue.drain(10, Duration.ZERO).get(0).unknownEvent()).isTrue();
    }

    @Test
    @DisplayName("请求 501 KB → 413 ProblemDetail（按 Content-Length 判，不解析）")
    void requestTooLarge() throws Exception {
        String pad = "x".repeat(501 * 1024);
        assertProblem(postBody(batchOf(event("a", ",\"properties\":{\"p\":\"" + pad + "\"}"))), 413);
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("无 Content-Length（chunked）时边读边计数：超限即停并返回 null；未超限读全")
    void boundedReadWithoutContentLength() throws IOException {
        assertThat(AnalyticsBatchController.readBounded(chunked(new byte[1001]), 1000)).isNull();
        assertThat(AnalyticsBatchController.readBounded(chunked(new byte[1000]), 1000)).hasSize(1000);
        assertThat(AnalyticsBatchController.readBounded(chunked(new byte[0]), 1000)).isEmpty();
    }

    private static MockHttpServletRequest chunked(byte[] data) {
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", PATH) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override
                    public int read() {
                        return in.read();
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        return in.read(b, off, len);
                    }

                    @Override
                    public boolean isFinished() {
                        return in.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener l) {
                    }
                };
            }
        };
        return req;
    }

    @Test
    @DisplayName("一条 33 KB → 整批 413")
    void eventTooLarge() throws Exception {
        String pad = "x".repeat(33 * 1024);
        assertProblem(postBody(batchOf(event("a", ""), event("b", ",\"properties\":{\"p\":\"" + pad + "\"}"))), 413);
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("batch 501 条 → 413")
    void tooManyEvents() throws Exception {
        String[] events = new String[501];
        for (int i = 0; i < events.length; i++) {
            events[i] = "{}";
        }
        assertProblem(postBody(batchOf(events)), 413);
    }

    @Test
    @DisplayName("非 JSON / 根不是对象 / batch 不是数组 / batch 缺失 / batch 为空 → 400 ProblemDetail")
    void malformed() throws Exception {
        for (String body : List.of("not json", "[1,2]", "\"str\"", "{\"batch\":{}}", "{\"batch\":\"x\"}", "{}",
                "{\"batch\":[]}", "", "{\"batch\":[1,")) {
            assertProblem(postBody(body), 400);
        }
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("管道停用（含清单无效）→ 503 + Retry-After")
    void pipelineDisabled() throws Exception {
        state.disable(Reason.CATALOG_INVALID, "test");

        ResultActions r = postBody(batchOf(event("a", "")));

        assertProblem(r, 503);
        r.andExpect(header().string("Retry-After", String.valueOf(AnalyticsBatchController.UNAVAILABLE_RETRY_AFTER_SECONDS)));
        verify(limiter, never()).tryAcquire(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("停机中（队列已关闭）→ 503 + Retry-After")
    void shuttingDown() throws Exception {
        queue.close();

        ResultActions r = postBody(batchOf(event("a", "")));

        assertProblem(r, 503);
        r.andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("匿名 id 超限 → 429 + Retry-After（窗口剩余秒数），不入队")
    void perDeviceRateLimited() throws Exception {
        when(limiter.tryAcquire(eq(AnalyticsBatchController.RL_AID_PREFIX + AID), anyInt(), any())).thenReturn(false);
        when(limiter.secondsUntilReset(eq(AnalyticsBatchController.RL_AID_PREFIX + AID), any())).thenReturn(17L);

        ResultActions r = postBody(batchOf(event("a", "")));

        assertProblem(r, 429);
        r.andExpect(header().string("Retry-After", "17"))
                .andExpect(jsonPath("$.type").value("https://petgo/errors/rate-limited"));
        assertThat(queue.size()).isZero();
        verify(limiter).tryAcquire(eq(AnalyticsBatchController.RL_AID_PREFIX + AID), eq(120), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("IP 兜底超限 → 429；IP 只以哈希前 16 位进键")
    void perIpRateLimited() throws Exception {
        when(limiter.tryAcquire(startsWith(AnalyticsBatchController.RL_IP_PREFIX), anyInt(), any())).thenReturn(false);
        when(limiter.secondsUntilReset(anyString(), any())).thenReturn(60L);

        ResultActions r = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("CF-Connecting-IP", "203.0.113.9").content(batchOf(event("a", ""))));

        assertProblem(r, 429);
        r.andExpect(header().string("Retry-After", "60"));
        String key = AnalyticsBatchController.RL_IP_PREFIX + AnalyticsBatchController.ipHash("203.0.113.9");
        verify(limiter).tryAcquire(eq(key), eq(3000), eq(Duration.ofSeconds(60)));
        assertThat(key).doesNotContain("203.0.113.9").hasSize(AnalyticsBatchController.RL_IP_PREFIX.length() + 16);
    }

    @Test
    @DisplayName("Redis 异常 → 放行（202）")
    void redisFailureAllows() throws Exception {
        when(limiter.tryAcquire(anyString(), anyInt(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        postBody(batchOf(event("a", ""))).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("取不到 anonymousId 时只走 IP 兜底；超长串不进 Redis 键")
    void rawAnonymousIdsForRateLimit() throws Exception {
        postBody(batchOf("{\"event\":\"x\"}", "{\"anonymousId\":\"" + "z".repeat(65) + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.rejected").value(2));

        verify(limiter, never()).tryAcquire(startsWith(AnalyticsBatchController.RL_AID_PREFIX), anyInt(), any());
        verify(limiter).tryAcquire(startsWith(AnalyticsBatchController.RL_IP_PREFIX), anyInt(), any());
    }

    @Test
    @DisplayName("同批多个不同 anonymousId 各计一次，大小写视为同一个")
    void distinctAnonymousIdsCountedOnce() throws Exception {
        String other = "11111111-2222-4333-8444-555555555555";
        postBody(batchOf(event("a", ""), event("b", ""), event("c", "").replace(AID, AID.toUpperCase()),
                event("d", "").replace(AID, other)))
                .andExpect(status().isAccepted());

        verify(limiter).tryAcquire(eq(AnalyticsBatchController.RL_AID_PREFIX + AID), anyInt(), any());
        verify(limiter).tryAcquire(eq(AnalyticsBatchController.RL_AID_PREFIX + other), anyInt(), any());
    }

    @Test
    @DisplayName("队列满 → 429 + Retry-After（配置值），整批不进")
    void queueFull() throws Exception {
        queue = new IngestQueue(10);
        mvc = build(IngestProperties.defaults());

        ResultActions r = postBody(batchOf(event("a", ""), event("b", "")));

        assertProblem(r, 429);
        r.andExpect(header().string("Retry-After", "30"));
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("Story 1.10 停写熔断 → 429 + Retry-After 300（与队列满同一套构造），不消耗限流配额、整批不进；恢复后 202")
    void writeStoppedByBreaker() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        queue = new IngestQueue(64L * 1024 * 1024, open::get);
        mvc = build(IngestProperties.defaults());

        ResultActions r = postBody(batchOf(event("a", "")));

        assertProblem(r, 429);
        r.andExpect(header().string("Retry-After", "300"));
        assertThat(queue.size()).isZero();
        verify(limiter, never()).tryAcquire(anyString(), anyInt(), any());

        open.set(false);
        postBody(batchOf(event("b", ""))).andExpect(status().isAccepted());
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Story 1.10：管道停用优先于熔断 → 仍是 503")
    void disabledBeatsBreaker() throws Exception {
        queue = new IngestQueue(64L * 1024 * 1024, () -> true);
        mvc = build(IngestProperties.defaults());
        state.disable(Reason.DB_UNAVAILABLE, "x");

        assertProblem(postBody(batchOf(event("a", ""))), 503);
    }

    @Test
    @DisplayName("全部不合格 → 202 {accepted:0, rejected:n}")
    void allRejected() throws Exception {
        postBody(batchOf("{}", "{\"messageId\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected").value(2));
    }

    @Test
    @DisplayName("UTF-8 多字节内容照常解析")
    void utf8() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(batchOf(event("a", ",\"properties\":{\"name\":\"Kucing 猫 🐱\"}"))
                                .getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted());

        assertThat(queue.drain(1, Duration.ZERO).get(0).propertiesJson()).contains("猫");
    }
}

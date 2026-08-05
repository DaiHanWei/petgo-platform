package com.tailtopia.shared.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * PostHog 上报的 **HTTP 线路形态**（V1.1.2 Story 6.1 · T-12 · code-review 2026-08-04 新增）。
 *
 * <p>为什么必须有这一层：原先 9 个用例全部通过一个假 {@code AnalyticsClient} 断言，
 * 于是**真正可能出错的东西一个都没被验证** —— 端点路径、{@code api_key} 字段名、
 * {@code distinct_id} 放在 properties 里而不是顶层、以及有没有带 {@code timestamp}。
 * 这些只要错一处，看板上就一条 {@code milestone_achieved} 都没有，而后端因为吞错只留一行 warn。
 *
 * <p>同样重要的是**关闭态**：原先那条「key 留空不出网」的用例是恒绿的（真发了请求也会被
 * {@code catch} 吞掉，用例除了同义反复什么都没断言）。这里改用 {@code MockRestServiceServer}
 * ——它对任何未预期的请求会直接失败，才是真的守护。
 */
class PostHogAnalyticsClientTest {

    private static final String KEY = "phc_test_write_only";

    @Test
    @DisplayName("启用态：POST /i/v0/e/，body 含 api_key/event/timestamp，distinct_id 在 properties 里")
    void sendsExpectedWireFormat() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ph.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ph.test/i/v0/e/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.api_key").value(KEY))
                .andExpect(jsonPath("$.event").value("milestone_achieved"))
                // 事务提交后才异步上报，不带 timestamp 会让 PostHog 用摄取时间当事件时间，
                // 跨天边界把达成归到错误的日期上。
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.properties.distinct_id").value("hash-abc"))
                .andExpect(jsonPath("$.properties.code").value("C-M5"))
                .andExpect(jsonPath("$.properties.path").value("consult"))
                .andRespond(withSuccess());

        new PostHogAnalyticsClient(KEY, builder)
                .capture("hash-abc", "milestone_achieved", Map.of("code", "C-M5", "path", "consult"));

        server.verify();
    }

    @Test
    @DisplayName("key 留空 → 一个请求都不发（本地/测试默认状态）")
    void blankKeyMeansNoTrafficAtAll() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ph.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 刻意不设任何 expectation：MockRestServiceServer 对任何请求都会失败。
        PostHogAnalyticsClient client = new PostHogAnalyticsClient("", builder);

        assertThat(client.isEnabled()).isFalse();
        client.capture("hash-abc", "milestone_achieved", Map.of("code", "C-S1"));

        server.verify(); // 有任何请求发出这里就红
    }

    @Test
    @DisplayName("上报失败被吞掉，绝不外抛（埋点挂了不能影响里程碑落库）")
    void swallowsServerErrors() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ph.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ph.test/i/v0/e/")).andRespond(withServerError());

        new PostHogAnalyticsClient(KEY, builder)
                .capture("hash-abc", "milestone_achieved", Map.of("code", "C-S1"));

        server.verify();
    }

    @Test
    @DisplayName("host 为空串也回退到默认值，不会变成非法 baseUrl")
    void blankHostFallsBackToDefault() {
        assertThat(PostHogAnalyticsClient.resolveHost("")).isEqualTo(PostHogAnalyticsClient.DEFAULT_HOST);
        assertThat(PostHogAnalyticsClient.resolveHost("   ")).isEqualTo(PostHogAnalyticsClient.DEFAULT_HOST);
        assertThat(PostHogAnalyticsClient.resolveHost(null)).isEqualTo(PostHogAnalyticsClient.DEFAULT_HOST);
        assertThat(PostHogAnalyticsClient.resolveHost("https://own.host")).isEqualTo("https://own.host");
    }
}

package com.tailtopia.shared.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link AnalyticsClient} 的 PostHog HTTP 实现（V1.1.2 Story 6.1 · T-12）。
 *
 * <p>打的是 PostHog 的 capture 端点 {@code POST {host}/i/v0/e/}，body 四个字段：
 * {@code api_key} / {@code event} / {@code timestamp} / {@code properties}
 * （其中 {@code distinct_id} 放在 properties 里）。无需 SDK。
 *
 * <p><b>配置</b>（全部 env 注入，{@code .env.example} 只放占位）：
 * <ul>
 *   <li>{@code analytics.posthog.key} —— write-only project token；<b>留空 = 整个上报静默关闭</b>
 *       （测试与本地开发的默认状态，不会有出网请求）</li>
 *   <li>{@code analytics.posthog.host} —— 默认 {@code https://eu.i.posthog.com}，与客户端同一个
 *       project（否则前后端事件落在两个项目里，漏斗拼不起来）。
 *       <b>空串也回退到默认值</b>：Spring 的 {@code ${VAR:default}} 只在属性缺失时用默认值，
 *       运维把 {@code POSTHOG_HOST=} 留空会得到 {@code baseUrl("")} → 每条上报都抛非法 URI 而被
 *       下面吞掉，正是「以为在报其实没报」（code-review 2026-08-04）。</li>
 *   <li>{@code analytics.posthog.timeout-seconds} —— 默认 3s，见下。</li>
 * </ul>
 *
 * <p><b>必须有超时</b>（code-review 2026-08-04）：没有超时的出网调用会把线程永久挂住。
 * 本仓其余出网客户端（{@code MidtransGateway} / {@code GemPayGateway} /
 * {@code GeminiDeveloperApiClient} / {@code LiveTencentImClient}）全都显式设了，此处照同一范式。
 *
 * <p><b>专用线程池</b>：上报走 {@code @Async("analyticsExecutor")}，与业务 {@code @Async}
 * （里程碑自动完成、达成通知、注销级联）<b>不共池</b>。该池队列有界且满时直接丢弃 ——
 * 埋点是可损数据，宁可丢事件，绝不能因为 PostHog 侧慢就把业务异步饿死。
 *
 * <p><b>失败即放弃，不重试</b>：埋点是可损数据。为它加重试/落库补偿会引入状态机与新表，
 * 代价远大于收益 —— 丢几条事件可以接受，拖慢里程碑落库不行。
 */
@Component
public class PostHogAnalyticsClient implements AnalyticsClient {

    private static final Logger log = LoggerFactory.getLogger(PostHogAnalyticsClient.class);

    /** host 缺失或为空串时的回退值。 */
    static final String DEFAULT_HOST = "https://eu.i.posthog.com";

    private final String apiKey;
    private final RestClient rest;

    // 有两个构造器（另一个是下面的测试注入点），必须显式告诉容器用哪个 —— 否则
    // 「No default constructor found」，整个 ApplicationContext 起不来。
    @Autowired
    public PostHogAnalyticsClient(
            @Value("${analytics.posthog.key:}") String apiKey,
            @Value("${analytics.posthog.host:}") String host,
            @Value("${analytics.posthog.timeout-seconds:3}") long timeoutSeconds) {
        this(apiKey, builderFor(resolveHost(host), timeoutSeconds));
        // 启动时把状态说清楚（不打 key）：「以为在报其实没报」只能靠这一行在日志里被发现。
        log.info("server-side analytics enabled={} host={}", isEnabled(), resolveHost(host));
    }

    /**
     * 测试注入点（同包可见）：允许绑定 {@code MockRestServiceServer} 的 builder，
     * 从而真正验证 HTTP 线路形态（端点 / 字段名 / 关闭时不发请求）。
     */
    PostHogAnalyticsClient(String apiKey, RestClient.Builder builder) {
        this.apiKey = apiKey;
        this.rest = builder.build();
    }

    static String resolveHost(String host) {
        return host == null || host.isBlank() ? DEFAULT_HOST : host;
    }

    private static RestClient.Builder builderFor(String host, long timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeout);
        rf.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(host).requestFactory(rf);
    }

    /** 上报是否启用（key 为空 → 关闭）。供调用方与测试判读，避免「以为在报其实没报」。 */
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Async("analyticsExecutor")
    @Override
    public void capture(String distinctId, String event, Map<String, Object> properties) {
        if (!isEnabled()) {
            return;
        }
        try {
            Map<String, Object> props = new HashMap<>(properties == null ? Map.of() : properties);
            props.put("distinct_id", distinctId);
            rest.post()
                    .uri("/i/v0/e/")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "api_key", apiKey,
                            "event", event,
                            // 必须显式带时间：上报是「事务提交后异步」的，不带的话 PostHog 会用
                            // 摄取时间当事件时间，跨天边界会把达成归到错误的日期上。
                            "timestamp", Instant.now().toString(),
                            "properties", props))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // 事件名可入日志（受控字面量）；properties 不入（虽已约定无 PII，仍不给意外留口子）。
            log.warn("analytics capture failed: event={} reason={}", event, e.getClass().getSimpleName());
        }
    }
}

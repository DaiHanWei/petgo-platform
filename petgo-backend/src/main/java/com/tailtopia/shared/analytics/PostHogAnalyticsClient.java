package com.tailtopia.shared.analytics;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link AnalyticsClient} 的 PostHog HTTP 实现（V1.1.2 Story 6.1 · T-12）。
 *
 * <p>打的是 PostHog 的 capture 端点 {@code POST {host}/i/v0/e/}，body 三个字段：
 * {@code api_key} / {@code event} / {@code properties}（其中 {@code distinct_id} 放在 properties 里）。
 * 无需 SDK。
 *
 * <p><b>配置</b>（全部 env 注入，{@code .env.example} 只放占位）：
 * <ul>
 *   <li>{@code analytics.posthog.key} —— write-only project token；<b>留空 = 整个上报静默关闭</b>
 *       （测试与本地开发的默认状态，不会有出网请求）</li>
 *   <li>{@code analytics.posthog.host} —— 默认 {@code https://eu.i.posthog.com}，与客户端同一个
 *       project（否则前后端事件落在两个项目里，漏斗拼不起来）</li>
 * </ul>
 *
 * <p><b>失败即放弃，不重试</b>：埋点是可损数据。为它加重试/落库补偿会引入状态机与新表，
 * 代价远大于收益 —— 丢几条事件可以接受，拖慢里程碑落库不行。
 */
@Component
public class PostHogAnalyticsClient implements AnalyticsClient {

    private static final Logger log = LoggerFactory.getLogger(PostHogAnalyticsClient.class);

    private final String apiKey;
    private final RestClient rest;

    public PostHogAnalyticsClient(
            @Value("${analytics.posthog.key:}") String apiKey,
            @Value("${analytics.posthog.host:https://eu.i.posthog.com}") String host) {
        this.apiKey = apiKey;
        this.rest = RestClient.builder().baseUrl(host).build();
    }

    /** 上报是否启用（key 为空 → 关闭）。供调用方与测试判读，避免「以为在报其实没报」。 */
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Async
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
                    .body(Map.of("api_key", apiKey, "event", event, "properties", props))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // 事件名可入日志（受控字面量）；properties 不入（虽已约定无 PII，仍不给意外留口子）。
            log.warn("analytics capture failed: event={} reason={}", event, e.getClass().getSimpleName());
        }
    }
}

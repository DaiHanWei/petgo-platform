package com.tailtopia.admin.aiquery.agent;

import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 调 ai-query-agent 的内部接口（spec §4.2）：内网 HTTP + 请求头 {@code X-Internal-Secret}。
 *
 * <p>🔴 请求体含问题原文：<b>任何分支都不记录请求体或响应体</b>，日志只出现任务 token 与状态码。
 * 地址与密钥每次从 {@link AiQueryProperties} 读取（env 注入，改配置不需要重建 bean）。
 */
@Component
public class AiQueryAgentClient {

    private static final Logger log = LoggerFactory.getLogger(AiQueryAgentClient.class);
    static final String SECRET_HEADER = "X-Internal-Secret";

    /** 提交结果。 */
    public enum SubmitOutcome {
        /** 202：已接收（含幂等重复提交）。 */
        ACCEPTED,
        /** 429 / 不可达 / 5xx / 未配置 / 密钥不符：保持 PENDING，下轮重试，到时转 TIMEOUT。 */
        RETRY,
        /** 400 等：请求本身不合法，重试也不会成功。 */
        REJECTED
    }

    /** 轮询结果。 */
    public sealed interface PollOutcome {
        record Found(AgentJob job) implements PollOutcome {
        }

        /** 404：agent 不认识该任务（含 agent 重启丢失运行中任务）。 */
        record Unknown() implements PollOutcome {
        }

        /** 不可达 / 5xx / 未配置：本轮跳过。 */
        record Unreachable() implements PollOutcome {
        }
    }

    /** 密钥不符的 error 日志限频：同一问题每 5 分钟最多记一次（扫描器每 3 秒一轮）。 */
    private static final long AUTH_LOG_INTERVAL_MS = 5 * 60_000L;
    private final java.util.concurrent.atomic.AtomicLong lastAuthLog = new java.util.concurrent.atomic.AtomicLong();

    private final AiQueryProperties props;
    private final RestClient rest;

    public AiQueryAgentClient(AiQueryProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(3));
        rf.setReadTimeout(Duration.ofSeconds(10));
        this.rest = RestClient.builder().requestFactory(rf).build();
    }

    public SubmitOutcome submit(String jobToken, String conversationToken, String question, String lang,
            String resumeSessionId) {
        if (!props.agentConfigured()) {
            return SubmitOutcome.RETRY;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobToken", jobToken);
        body.put("conversationToken", conversationToken);
        body.put("question", question);
        body.put("lang", lang);
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            body.put("resumeSessionId", resumeSessionId);
        }
        try {
            HttpStatusCode code = rest.post().uri(props.getAgentUrl() + "/jobs")
                    .header(SECRET_HEADER, props.getAgentSecret())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            return code.value() == 202 || code.value() == 200 ? SubmitOutcome.ACCEPTED : SubmitOutcome.RETRY;
        } catch (RestClientResponseException e) {
            int s = e.getStatusCode().value();
            if (s == 400 || s == 413) {
                // 只有「请求本身不合法」才判死；其余 4xx（401 / 403 / 404 …）多是地址或密钥配错，修好配置后应能继续
                log.warn("ai-query agent rejected job submission status={} jobToken={}", s, jobToken);
                return SubmitOutcome.REJECTED;
            }
            if (s == 401) {
                logAuthMismatch(jobToken);
            } else if (s != 429 && s < 500) {
                log.warn("ai-query agent answered status={} on submit (check AI_QUERY_AGENT_URL) jobToken={}", s, jobToken);
            }
            return SubmitOutcome.RETRY;
        } catch (RestClientException e) {
            log.warn("ai-query agent unreachable on submit jobToken={} cause={}", jobToken, e.getClass().getSimpleName());
            return SubmitOutcome.RETRY;
        }
    }

    private void logAuthMismatch(String jobToken) {
        long now = System.currentTimeMillis();
        long last = lastAuthLog.get();
        if (now - last >= AUTH_LOG_INTERVAL_MS && lastAuthLog.compareAndSet(last, now)) {
            log.error("ai-query agent rejected the shared secret (401), check AI_QUERY_AGENT_SECRET jobToken={}", jobToken);
        }
    }

    public PollOutcome poll(String jobToken) {
        if (!props.agentConfigured()) {
            return new PollOutcome.Unreachable();
        }
        try {
            AgentJob job = rest.get().uri(props.getAgentUrl() + "/jobs/{t}", jobToken)
                    .header(SECRET_HEADER, props.getAgentSecret())
                    .retrieve()
                    .body(AgentJob.class);
            return job == null ? new PollOutcome.Unreachable() : new PollOutcome.Found(job);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return new PollOutcome.Unknown();
            }
            if (e.getStatusCode().value() == 401) {
                logAuthMismatch(jobToken);
            }
            return new PollOutcome.Unreachable();
        } catch (RestClientException e) {
            log.warn("ai-query agent unreachable on poll jobToken={} cause={}", jobToken, e.getClass().getSimpleName());
            return new PollOutcome.Unreachable();
        }
    }
}

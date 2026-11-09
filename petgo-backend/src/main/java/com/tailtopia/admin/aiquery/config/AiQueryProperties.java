package com.tailtopia.admin.aiquery.config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ops AI 查数配置（主题 ops-ai-query，spec §5.6）。前缀 {@code petgo.ai-query}，全部 env 注入。
 *
 * <p>{@code mode}：{@code off}（默认）= 页面提示未启用、输入禁用、扫描器不工作；{@code on} = 开启。
 * 回退只需把 env {@code AI_QUERY_MODE} 改回 {@code off}（spec §10）。
 */
@ConfigurationProperties(prefix = "petgo.ai-query")
public class AiQueryProperties {

    /** {@code off}（默认）| {@code on}。其他值一律按 off 处理，宁可关也不误开。 */
    private String mode = "off";

    /** agent 内网地址，如 {@code http://ai-query-agent-stag:8790}（env {@code AI_QUERY_AGENT_URL}）。 */
    private String agentUrl = "";

    /** 调 agent 的共享密钥，请求头 {@code X-Internal-Secret}（env {@code AI_QUERY_AGENT_SECRET}）。绝不入库、不落日志。 */
    private String agentSecret = "";

    /** 未结束任务的超时（spec §4.1：agent 自身 10 分钟超时 + 排队余量）。 */
    private int timeoutMinutes = 15;

    /** 问答、导出文件、Claude 会话记录的保留天数（spec D6，env {@code AI_QUERY_RETENTION_DAYS}）。 */
    private int retentionDays = 30;

    public boolean isEnabled() {
        return "on".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    /** 保留期起点：对话 updated_at 早于此即视为已过期（清理任务可能晚一轮才真正删除）。 */
    public Instant notBefore() {
        return Instant.now().minus(retentionDays, ChronoUnit.DAYS);
    }

    /** agent 已配置（地址与密钥都有）。 */
    public boolean agentConfigured() {
        return agentUrl != null && !agentUrl.isBlank() && agentSecret != null && !agentSecret.isBlank();
    }

    public String getAgentUrl() {
        return agentUrl;
    }

    public void setAgentUrl(String agentUrl) {
        this.agentUrl = agentUrl == null ? "" : agentUrl.strip().replaceAll("/+$", "");
    }

    public String getAgentSecret() {
        return agentSecret;
    }

    public void setAgentSecret(String agentSecret) {
        this.agentSecret = agentSecret == null ? "" : agentSecret.strip();
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public void setTimeoutMinutes(int timeoutMinutes) {
        if (timeoutMinutes < 1) {
            throw new IllegalArgumentException("petgo.ai-query.timeout-minutes 必须 ≥ 1");
        }
        this.timeoutMinutes = timeoutMinutes;
    }

    /** 不在 toString 里出现密钥。 */
    @Override
    public String toString() {
        return "AiQueryProperties{mode=" + mode + ", agentUrl=" + agentUrl + ", agentSecret=" + (agentSecret.isEmpty() ? "" : "***")
                + ", timeoutMinutes=" + timeoutMinutes + ", retentionDays=" + retentionDays + "}";
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("petgo.ai-query.retention-days 必须 ≥ 1");
        }
        this.retentionDays = retentionDays;
    }
}

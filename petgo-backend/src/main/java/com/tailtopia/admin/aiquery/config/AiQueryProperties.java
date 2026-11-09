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

package com.tailtopia.admin.aiquery.config;

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

    public boolean isEnabled() {
        return "on".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}

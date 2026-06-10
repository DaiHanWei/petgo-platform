package com.petgo.shared.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini 接入配置（Story 4.1）。前缀 {@code petgo.ai.gemini}。
 *
 * <p>护栏：{@code apiKey} 全部从 env（{@code GEMINI_API_KEY}）注入，<b>绝不入库、绝不落日志</b>；
 * {@code .env.example} 仅放占位。{@code mode=stub}（默认）走打桩客户端，使状态机/重试在无外部凭证下
 * 可 L0/L1 验证；{@code mode=live} 才打真实 Gemini Developer API（L2，需真实 key）。
 *
 * <p>接口抽象保留以便未来迁 Vertex（架构 G-3 数据出境挂账）。
 */
@ConfigurationProperties(prefix = "petgo.ai.gemini")
public class GeminiProperties {

    /** {@code stub} | {@code live}。默认 stub，应用无 key 也能启动并跑通状态机。 */
    private String mode = "stub";

    /** Gemini Developer API key（env 注入，绝不入库/落日志）。 */
    private String apiKey = "";

    /** 模型别名。默认 gemini-2.5-flash（若执行时有更新别名以实际为准，勿降级能力）。 */
    private String model = "gemini-2.5-flash";

    /** Developer API base（generativelanguage）。 */
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

    /** 单次调用超时（秒）。贴合 ≤15s SLA，预留重试余量。 */
    private int timeoutSeconds = 10;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}

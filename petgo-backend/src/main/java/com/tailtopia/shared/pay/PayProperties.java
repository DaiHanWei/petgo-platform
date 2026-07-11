package com.tailtopia.shared.pay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 支付网关接入配置（Story 1.1）。前缀 {@code petgo.pay}。
 *
 * <p>护栏：{@code serverKey} / {@code callbackToken} 全部从 env（{@code MIDTRANS_SERVER_KEY} /
 * {@code MIDTRANS_CALLBACK_TOKEN}）注入，<b>绝不入库、绝不落日志</b>；{@code .env.example} 仅放占位。
 * {@code mode=stub}（默认）走桩网关，使意图状态机 / 回调去重在无外部凭证下可 L0/L1 验证；
 * {@code mode=live} 才打真实 Midtrans（L2，需 sandbox 凭证）。
 */
@ConfigurationProperties(prefix = "petgo.pay")
public class PayProperties {

    /** {@code stub} | {@code live}。默认 stub，应用无凭证也能装配启动。 */
    private String mode = "stub";

    /** Midtrans Server Key（env 注入，绝不入库/落日志）；Basic base64(serverKey:) 鉴权用。 */
    private String serverKey = "";

    /** Midtrans API base（默认 sandbox）。 */
    private String baseUrl = "https://api.sandbox.midtrans.com";

    /**
     * 回调校验令牌（stub 直比 {@code signature_key} 字段；live 用 serverKey 算 SHA-512，此字段仅
     * stub/内网白名单兜底用）。env 注入，绝不入库/落日志。
     */
    private String callbackToken = "";

    /** 单次网关调用超时（秒）。 */
    private int timeoutSeconds = 10;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getServerKey() {
        return serverKey;
    }

    public void setServerKey(String serverKey) {
        this.serverKey = serverKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCallbackToken() {
        return callbackToken;
    }

    public void setCallbackToken(String callbackToken) {
        this.callbackToken = callbackToken;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}

package com.tailtopia.admin.dailyreport;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 飞书群日报配置（2026-09-25）。前缀 {@code petgo.daily-report}。
 *
 * <p>🔴 {@code webhookUrl} 留空 = 整个推送静默跳过（本地 / 测试 / 未配置的环境天然关闭）。
 * webhook 与签名密钥 env 注入，<b>绝不入库、绝不落日志</b>。
 */
@ConfigurationProperties(prefix = "petgo.daily-report")
public class DailyReportProperties {

    /** 飞书群自定义机器人 webhook（env {@code LARK_DAILY_REPORT_WEBHOOK_URL}）。空 = 不推送。 */
    private String webhookUrl = "";

    /** 签名校验密钥（env {@code LARK_DAILY_REPORT_SECRET}）。空 = 机器人未开签名校验，payload 不带 sign。 */
    private String secret = "";

    /** HTTP 超时秒数。 */
    private int timeoutSeconds = 10;

    public boolean isEnabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}

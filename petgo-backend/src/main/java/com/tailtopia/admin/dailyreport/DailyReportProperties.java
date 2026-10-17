package com.tailtopia.admin.dailyreport;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 飞书群日报配置（2026-09-25）。前缀 {@code petgo.daily-report}。
 *
 * <p>🔴 {@code webhookUrl} 留空 = 整个推送静默跳过（本地 / 测试 / 未配置的环境天然关闭）。
 * webhook 与签名密钥 env 注入，<b>绝不入库、绝不落日志</b>。
 */
@ConfigurationProperties(prefix = "petgo.daily-report")
public class DailyReportProperties {

    /**
     * 飞书群自定义机器人 webhook（env {@code LARK_DAILY_REPORT_WEBHOOK_URL}）。空 = 不推送。
     * 多个群用英文逗号分隔，每个群各推一份。
     */
    private String webhookUrl = "";

    /**
     * 签名校验密钥（env {@code LARK_DAILY_REPORT_SECRET}）。空 = 机器人未开签名校验，payload 不带 sign。
     * 多个群时按位置与 {@link #webhookUrl} 一一对应（逗号分隔，某个群没开签名就留空位，如 {@code s1,,s3}）。
     */
    private String secret = "";

    /** 一个推送目标（一个群）。 */
    public record Target(String webhookUrl, String secret) {
    }

    /** HTTP 超时秒数。 */
    private int timeoutSeconds = 10;

    /** 「日活（含游客）」数据源：Firebase / GA4（spec-v132-ga4-dau-daily-report）。 */
    private final Ga4 ga4 = new Ga4();

    public Ga4 getGa4() {
        return ga4;
    }

    /**
     * GA4 Data API 配置。两项任一为空 = 不查 GA4，日报该字段显示「—」（本地 / 测试天然关闭）。
     * 服务账号密钥 env 注入，<b>绝不入库、绝不落日志</b>。
     */
    public static class Ga4 {

        /** GA4 媒体资源 ID（纯数字，env {@code GA4_PROPERTY_ID}）。属性时区须设为雅加达，日期才与 WIB 自然日对齐。 */
        private String propertyId = "";

        /** 服务账号 JSON 密钥的 base64（env {@code GA4_SA_KEY_B64}；env-file 不便放多行 JSON）。 */
        private String credentialsB64 = "";

        public boolean isEnabled() {
            return propertyId != null && !propertyId.isBlank()
                    && credentialsB64 != null && !credentialsB64.isBlank();
        }

        public String getPropertyId() {
            return propertyId;
        }

        public void setPropertyId(String propertyId) {
            this.propertyId = propertyId;
        }

        public String getCredentialsB64() {
            return credentialsB64;
        }

        public void setCredentialsB64(String credentialsB64) {
            this.credentialsB64 = credentialsB64;
        }
    }

    public boolean isEnabled() {
        return !targets().isEmpty();
    }

    /** 解析出的推送目标，按配置顺序；空白地址跳过，缺的密钥视为未开签名。 */
    public List<Target> targets() {
        List<Target> out = new ArrayList<>();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return out;
        }
        String[] urls = webhookUrl.split(",", -1);
        String[] secrets = secret == null ? new String[0] : secret.split(",", -1);
        for (int i = 0; i < urls.length; i++) {
            String url = urls[i].trim();
            if (url.isEmpty()) {
                continue;
            }
            out.add(new Target(url, i < secrets.length ? secrets[i].trim() : ""));
        }
        return out;
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

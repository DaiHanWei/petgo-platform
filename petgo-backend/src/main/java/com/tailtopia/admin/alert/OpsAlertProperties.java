package com.tailtopia.admin.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 电商运营提醒的三个飞书群自定义机器人（V1.4.2 shop-admin Story 1.3 · AD-5）。前缀 {@code petgo.shop.ops-alert}。
 *
 * <p>env：{@code LARK_SHOP_{CS,OPS,TECH}_WEBHOOK_URL} / {@code _SECRET}。🔴 URL 为空 = 该群不发，只写 WARN 日志
 * （本地 / 测试 / 未配置的环境天然关闭）。webhook 与密钥 env 注入，<b>绝不入库、绝不落日志</b>。
 */
@ConfigurationProperties(prefix = "petgo.shop.ops-alert")
public class OpsAlertProperties {

    private Target cs = new Target();
    private Target ops = new Target();
    private Target tech = new Target();

    /** 一个群的 webhook + 签名密钥（密钥空 = 机器人未开签名校验）。 */
    public static class Target {
        private String webhookUrl = "";
        private String secret = "";

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret == null ? "" : secret.trim();
        }

        public boolean isConfigured() {
            return webhookUrl != null && !webhookUrl.isBlank();
        }
    }

    public Target of(OpsChannel channel) {
        return switch (channel) {
            case CS -> cs;
            case OPS -> ops;
            case TECH -> tech;
        };
    }

    public Target getCs() {
        return cs;
    }

    public void setCs(Target cs) {
        this.cs = cs;
    }

    public Target getOps() {
        return ops;
    }

    public void setOps(Target ops) {
        this.ops = ops;
    }

    public Target getTech() {
        return tech;
    }

    public void setTech(Target tech) {
        this.tech = tech;
    }
}

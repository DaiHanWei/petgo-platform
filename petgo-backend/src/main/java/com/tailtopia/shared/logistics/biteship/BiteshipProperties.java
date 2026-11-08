package com.tailtopia.shared.logistics.biteship;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Biteship 接入配置（v1.4.2 shop Story 1.1，D-9 / P-7）。前缀 {@code petgo.shop.biteship}。
 *
 * <p>🔒 {@code apiKey} 只从 env {@code BITESHIP_API_KEY} 注入，<b>绝不入库、绝不落日志</b>；
 * {@link #toString()} 不含 key。云端与 L0 / L1 一律 {@code mode=stub}。
 */
@ConfigurationProperties(prefix = "petgo.shop.biteship")
public class BiteshipProperties {

    /** 不允许出现在询价快递列表里的快递：其 {@code price} 强制含保价费，会与本平台自算保价重复。 */
    static final Set<String> FORBIDDEN_COURIERS = Set.of("jntcargo");

    /** {@code stub} | {@code sandbox} | {@code live}。 */
    private String mode = "stub";
    private String apiKey = "";
    private String baseUrl = "https://api.biteship.com";
    /** 逗号分隔快递代码，如 {@code jne,sicepat,anteraja}。 */
    private String couriers = "jne,sicepat,anteraja";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 8000;
    /**
     * Webhook 路径密钥（v1.4.2 shop Story 5.1，env {@code BITESHIP_WEBHOOK_SECRET}）。为空 → Webhook 一律 404（fail closed）。
     * 🔒 绝不落日志。
     */
    private String webhookSecret = "";
    /** 下运单 {@code delivery_type}：{@code now} | {@code scheduled}（Story 5.1）。 */
    private String deliveryType = "now";
    /** Webhook 收件行最大处理次数（达到即告警 / 放弃，Story 5.1 AC7.3）。 */
    private int webhookMaxAttempts = 10;
    /**
     * Biteship 运单 {@code price} 是否已含保价费（V1.4.2 shop-admin Story 4.1 · AD-13，须在沙箱实测）。
     * 默认 false = 不含，预估余额按 {@code carrier_cost + insurance_cost} 扣；true = 只扣 {@code carrier_cost}，不重复加。
     */
    private boolean priceIncludesInsurance = false;

    /**
     * 规范化后的快递列表：小写、去空格、去重、保序，并剔除 {@link #FORBIDDEN_COURIERS}。
     */
    public Set<String> courierSet() {
        return Arrays.stream(couriers == null ? new String[0] : couriers.split(","))
                .map(c -> c.trim().toLowerCase(Locale.ROOT))
                .filter(c -> !c.isEmpty())
                .filter(c -> !FORBIDDEN_COURIERS.contains(c))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 请求体 {@code couriers} 字段：逗号分隔字符串。 */
    public String couriersCsv() {
        return String.join(",", courierSet());
    }

    /** 配置里是否写了被禁快递（装配时 WARN 用）。 */
    public boolean containsForbiddenCourier() {
        return couriers != null && Arrays.stream(couriers.split(","))
                .map(c -> c.trim().toLowerCase(Locale.ROOT))
                .anyMatch(FORBIDDEN_COURIERS::contains);
    }

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

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCouriers() {
        return couriers;
    }

    public void setCouriers(String couriers) {
        this.couriers = couriers;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getDeliveryType() {
        return deliveryType;
    }

    public void setDeliveryType(String deliveryType) {
        this.deliveryType = deliveryType;
    }

    public int getWebhookMaxAttempts() {
        return webhookMaxAttempts;
    }

    public void setWebhookMaxAttempts(int webhookMaxAttempts) {
        this.webhookMaxAttempts = webhookMaxAttempts;
    }

    public boolean isPriceIncludesInsurance() {
        return priceIncludesInsurance;
    }

    public void setPriceIncludesInsurance(boolean priceIncludesInsurance) {
        this.priceIncludesInsurance = priceIncludesInsurance;
    }

    /** 🔒 不含 key / Webhook 密钥。 */
    @Override
    public String toString() {
        return "BiteshipProperties{mode=" + mode + ", baseUrl=" + baseUrl
                + ", couriers=" + couriersCsv() + ", apiKeySet=" + (apiKey != null && !apiKey.isBlank())
                + ", connectTimeoutMs=" + connectTimeoutMs + ", readTimeoutMs=" + readTimeoutMs
                + ", webhookSecretSet=" + (webhookSecret != null && !webhookSecret.isBlank())
                + ", deliveryType=" + deliveryType + ", webhookMaxAttempts=" + webhookMaxAttempts + "}";
    }
}

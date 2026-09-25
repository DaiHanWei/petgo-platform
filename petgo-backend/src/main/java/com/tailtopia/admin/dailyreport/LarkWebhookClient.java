package com.tailtopia.admin.dailyreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 飞书群自定义机器人 webhook 发送器（JDK 原生 HttpClient）。
 *
 * <p>配了签名密钥就在 payload 顶层带 {@code timestamp}（秒）与 {@code sign}：
 * {@code sign = Base64(HmacSHA256(key = timestamp + "\n" + secret, data = 空))}（飞书自定义机器人规范）。
 *
 * <p>🔴 成功判据要<b>两个都满足</b>：HTTP 200 <b>且</b> body 里 {@code code == 0}。飞书对签名错误、
 * 关键词不匹配、频率超限都回 HTTP 200 + 非 0 code，只看状态码会把失败当成功。
 *
 * <p>🔒 webhook URL 含 token，<b>任何异常信息与日志都不带 URL</b>。
 */
@Component
public class LarkWebhookClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DailyReportProperties props;
    private final HttpClient http;
    private final Clock clock;

    public LarkWebhookClient(DailyReportProperties props) {
        this(props, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds())).build(), Clock.systemUTC());
    }

    LarkWebhookClient(DailyReportProperties props, HttpClient http, Clock clock) {
        this.props = props;
        this.http = http;
        this.clock = clock;
    }

    /** 发送失败（网络 / 非 200 / 非 0 code）。消息里不含 webhook URL。 */
    public static class LarkWebhookException extends RuntimeException {
        public LarkWebhookException(String message) {
            super(message);
        }
    }

    public void send(Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        String secret = props.getSecret();
        if (secret != null && !secret.isBlank()) {
            String ts = String.valueOf(clock.instant().getEpochSecond());
            body.put("timestamp", ts);
            body.put("sign", sign(ts, secret));
        }
        body.putAll(payload);

        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.getWebhookUrl()))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LarkWebhookException("飞书 webhook 调用被中断");
        } catch (Exception e) {
            throw new LarkWebhookException("飞书 webhook 调用失败: " + e.getClass().getSimpleName());
        }
        if (resp.statusCode() != 200) {
            throw new LarkWebhookException("飞书 webhook HTTP " + resp.statusCode());
        }
        Integer code = codeOf(resp.body());
        if (code == null || code != 0) {
            throw new LarkWebhookException("飞书 webhook 返回非 0 code: " + code + " msg=" + msgOf(resp.body()));
        }
    }

    static String sign(String timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            String key = timestamp + "\n" + secret;
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    private static Integer codeOf(String body) {
        JsonNode n = parse(body);
        if (n == null) {
            return null;
        }
        // 新版返回 code；个别旧版只有 StatusCode —— 两个都认
        JsonNode code = n.has("code") ? n.get("code") : n.get("StatusCode");
        return code != null && code.canConvertToInt() ? code.asInt() : null;
    }

    private static String msgOf(String body) {
        JsonNode n = parse(body);
        if (n == null) {
            return "?";
        }
        JsonNode m = n.has("msg") ? n.get("msg") : n.get("StatusMessage");
        String s = m == null ? "?" : m.asText();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private static JsonNode parse(String body) {
        try {
            return body == null ? null : JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }
}

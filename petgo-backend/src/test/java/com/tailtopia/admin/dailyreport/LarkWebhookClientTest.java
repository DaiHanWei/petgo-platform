package com.tailtopia.admin.dailyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：飞书 webhook 发送（本地假服务器，不出网）。 */
class LarkWebhookClientTest {

    private HttpServer server;
    private final AtomicReference<String> received = new AtomicReference<>();

    private String serve(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private LarkWebhookClient client(String url, String secret) {
        DailyReportProperties p = new DailyReportProperties();
        p.setWebhookUrl(url);
        p.setSecret(secret);
        p.setTimeoutSeconds(3);
        Clock fixed = Clock.fixed(Instant.ofEpochSecond(1599360473L), ZoneOffset.UTC);
        return new LarkWebhookClient(p, HttpClient.newHttpClient(), fixed);
    }

    @Test
    @DisplayName("签名算法：Base64(HmacSHA256(key=ts+\\n+secret, data=空)) —— 与独立实现的已知向量一致")
    void signKnownVector() {
        assertThat(LarkWebhookClient.sign("1599360473", "test-secret"))
                .isEqualTo("wSds2BzzFIIGf/WrhUO+NI1q/9j+FRJd3JNHKAq0NZY=");
    }

    @Test
    @DisplayName("配了密钥 → payload 顶层带 timestamp + sign，业务字段原样保留")
    void signedPayload() throws Exception {
        String url = serve(200, "{\"code\":0,\"msg\":\"success\"}");
        client(url, "test-secret").send(Map.of("msg_type", "interactive"));

        JsonNode sent = new ObjectMapper().readTree(received.get());
        assertThat(sent.get("timestamp").asText()).isEqualTo("1599360473");
        assertThat(sent.get("sign").asText()).isEqualTo("wSds2BzzFIIGf/WrhUO+NI1q/9j+FRJd3JNHKAq0NZY=");
        assertThat(sent.get("msg_type").asText()).isEqualTo("interactive");
    }

    @Test
    @DisplayName("没配密钥 → 不带 timestamp / sign")
    void unsignedPayload() throws Exception {
        String url = serve(200, "{\"code\":0}");
        client(url, "").send(Map.of("msg_type", "interactive"));

        JsonNode sent = new ObjectMapper().readTree(received.get());
        assertThat(sent.has("sign")).isFalse();
        assertThat(sent.has("timestamp")).isFalse();
    }

    @Test
    @DisplayName("🔴 HTTP 200 但 code≠0（签名错 / 频率超限）→ 判失败，不能只看状态码")
    void nonZeroCodeFails() throws Exception {
        String url = serve(200, "{\"code\":19021,\"msg\":\"sign match fail or timestamp is not within one hour\"}");

        assertThatThrownBy(() -> client(url, "x").send(Map.of("msg_type", "interactive")))
                .isInstanceOf(LarkWebhookClient.LarkWebhookException.class)
                .hasMessageContaining("19021")
                .hasMessageNotContaining(url);
    }

    @Test
    @DisplayName("非 200 → 失败；异常信息不含 webhook URL（URL 里有 token）")
    void httpErrorFails() throws Exception {
        String url = serve(500, "oops");

        assertThatThrownBy(() -> client(url, "").send(Map.of()))
                .isInstanceOf(LarkWebhookClient.LarkWebhookException.class)
                .hasMessageContaining("500")
                .hasMessageNotContaining(url);
    }

    @Test
    @DisplayName("body 不是 JSON / 没有 code → 失败")
    void garbageBodyFails() throws Exception {
        String url = serve(200, "<html>ok</html>");

        assertThatThrownBy(() -> client(url, "").send(Map.of()))
                .isInstanceOf(LarkWebhookClient.LarkWebhookException.class);
    }
}

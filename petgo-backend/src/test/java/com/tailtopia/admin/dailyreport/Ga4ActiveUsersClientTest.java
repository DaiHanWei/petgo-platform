package com.tailtopia.admin.dailyreport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：GA4 日活取数（本地假 token 端点 + 假 Data API，不出网、无真凭证）。 */
class Ga4ActiveUsersClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-06T06:00:00Z");

    private HttpServer server;
    private String base;
    private KeyPair keys;

    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicReference<String> assertion = new AtomicReference<>();
    private final List<String> reportBodies = new CopyOnWriteArrayList<>();
    private final List<String> reportAuth = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> reportPath = new AtomicReference<>();

    private volatile int reportStatus = 200;
    private volatile String reportResponse = "{\"rows\":[{\"metricValues\":[{\"value\":\"812\"}]}]}";

    @BeforeEach
    void start() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", ex -> {
            int n = tokenCalls.incrementAndGet();
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String kv : form.split("&")) {
                if (kv.startsWith("assertion=")) {
                    assertion.set(URLDecoder.decode(kv.substring("assertion=".length()), StandardCharsets.UTF_8));
                }
            }
            reply(ex, 200, "{\"access_token\":\"tok-" + n + "\",\"expires_in\":3600}");
        });
        server.createContext("/v1beta/", ex -> {
            reportPath.set(ex.getRequestURI().getPath());
            reportAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
            reportBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, reportStatus, reportResponse);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    /** 与 Google 下发的服务账号 JSON 同结构，token_uri 指向本地假端点。 */
    private String credentialsB64() throws Exception {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String json = JSON.writeValueAsString(Map.of(
                "type", "service_account",
                "client_email", "daily-report@tailtopia-ba4f7.iam.gserviceaccount.com",
                "private_key", pem,
                "token_uri", base + "/token"));
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private Ga4ActiveUsersClient client(String propertyId, String creds) {
        DailyReportProperties p = new DailyReportProperties();
        p.setTimeoutSeconds(3);
        p.getGa4().setPropertyId(propertyId);
        p.getGa4().setCredentialsB64(creds);
        return new Ga4ActiveUsersClient(p, HttpClient.newHttpClient(), Clock.fixed(NOW, ZoneOffset.UTC), base);
    }

    @Test
    @DisplayName("正常：换 token → runReport 查当天 activeUsers，带 Bearer、日期起止同一天")
    void happyPath() throws Exception {
        Long dau = client("123456", credentialsB64()).activeUsers(LocalDate.of(2026, 10, 5));

        assertThat(dau).isEqualTo(812L);
        assertThat(reportPath.get()).isEqualTo("/v1beta/properties/123456:runReport");
        assertThat(reportAuth.getFirst()).isEqualTo("Bearer tok-1");
        JsonNode body = JSON.readTree(reportBodies.getFirst());
        assertThat(body.at("/dateRanges/0/startDate").asText()).isEqualTo("2026-10-05");
        assertThat(body.at("/dateRanges/0/endDate").asText()).isEqualTo("2026-10-05");
        assertThat(body.at("/metrics/0/name").asText()).isEqualTo("activeUsers");
    }

    @Test
    @DisplayName("JWT：RS256 可被服务账号公钥验签；iss / aud / scope / 1 小时有效期正确")
    void jwtClaimsAndSignature() throws Exception {
        client("123456", credentialsB64()).activeUsers(LocalDate.of(2026, 10, 5));

        String[] parts = assertion.get().split("\\.");
        assertThat(parts).hasSize(3);
        Base64.Decoder dec = Base64.getUrlDecoder();
        assertThat(JSON.readTree(dec.decode(parts[0])).path("alg").asText()).isEqualTo("RS256");
        JsonNode claims = JSON.readTree(dec.decode(parts[1]));
        assertThat(claims.path("iss").asText()).isEqualTo("daily-report@tailtopia-ba4f7.iam.gserviceaccount.com");
        assertThat(claims.path("aud").asText()).isEqualTo(base + "/token");
        assertThat(claims.path("scope").asText()).isEqualTo(Ga4ActiveUsersClient.SCOPE);
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(3600);
        assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond());

        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(keys.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(dec.decode(parts[2]))).isTrue();
    }

    @Test
    @DisplayName("token 在有效期内复用：同一份日报查两天只换一次 token")
    void tokenCached() throws Exception {
        Ga4ActiveUsersClient c = client("123456", credentialsB64());
        c.activeUsers(LocalDate.of(2026, 10, 5));
        c.activeUsers(LocalDate.of(2026, 10, 4));

        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThat(reportAuth).containsExactly("Bearer tok-1", "Bearer tok-1");
    }

    @Test
    @DisplayName("401（token 被吊销 / 密钥轮换）→ 本次 null，下次重新换 token")
    void unauthorizedClearsToken() throws Exception {
        Ga4ActiveUsersClient c = client("123456", credentialsB64());
        reportStatus = 401;
        assertThat(c.activeUsers(LocalDate.of(2026, 10, 5))).isNull();

        reportStatus = 200;
        assertThat(c.activeUsers(LocalDate.of(2026, 10, 5))).isEqualTo(812L);
        assertThat(tokenCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("当天没有任何数据（响应无 rows）→ 0")
    void noRowsIsZero() throws Exception {
        reportResponse = "{\"rowCount\":0,\"metadata\":{}}";
        assertThat(client("123456", credentialsB64()).activeUsers(LocalDate.of(2026, 10, 5))).isZero();
    }

    @Test
    @DisplayName("🔴 失败一律 null、不抛：403 无权限 / 响应结构不符 / 密钥不是合法 base64")
    void failuresAreNull() throws Exception {
        reportStatus = 403;
        assertThat(client("123456", credentialsB64()).activeUsers(LocalDate.of(2026, 10, 5))).isNull();

        reportStatus = 200;
        reportResponse = "{\"rows\":[{\"metricValues\":[{}]}]}";
        assertThat(client("123456", credentialsB64()).activeUsers(LocalDate.of(2026, 10, 5))).isNull();

        assertThat(client("123456", "%%not-base64%%").activeUsers(LocalDate.of(2026, 10, 5))).isNull();
    }

    @Test
    @DisplayName("未配置（property-id 或密钥任一为空）→ null，且不发任何请求")
    void notConfiguredSendsNothing() throws Exception {
        assertThat(client("", credentialsB64()).activeUsers(LocalDate.of(2026, 10, 5))).isNull();
        assertThat(client("123456", "").activeUsers(LocalDate.of(2026, 10, 5))).isNull();

        assertThat(tokenCalls.get()).isZero();
        assertThat(reportBodies).isEmpty();
    }
}

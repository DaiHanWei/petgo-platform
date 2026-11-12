package com.tailtopia.admin.dailyreport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailtopia.admin.dailyreport.Ga4ActiveUsersClient.Ga4VersionSplit;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
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
    /** Story 4.2：按顺序逐次返回的（状态码, 响应体）；空了再回退到 reportStatus / reportResponse。 */
    private final Deque<Object[]> scripted = new ConcurrentLinkedDeque<>();

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
            Object[] next = scripted.poll();
            if (next != null) {
                reply(ex, (Integer) next[0], (String) next[1]);
            } else {
                reply(ex, reportStatus, reportResponse);
            }
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

    // ---------------------------------------------------------------- Story 4.2：按 appVersion 拆分

    private static String total(long n) {
        return "{\"rows\":[{\"metricValues\":[{\"value\":\"" + n + "\"}]}]}";
    }

    private static String versions(String... vs) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String v : vs) {
            rows.add(Map.of("dimensionValues", List.of(Map.of("value", v)),
                    "metricValues", List.of(Map.of("value", "7"))));
        }
        return JSON.writeValueAsString(Map.of("rows", rows));
    }

    private void script(int status, String body) {
        scripted.add(new Object[] {status, body});
    }

    @Test
    @DisplayName("4.2 isConfigured：两项都非空才为 true，与取数方法的「未配置」同一判据")
    void isConfigured() throws Exception {
        assertThat(client("123456", credentialsB64()).isConfigured()).isTrue();
        assertThat(client("", credentialsB64()).isConfigured()).isFalse();
        assertThat(client("123456", " ").isConfigured()).isFalse();
    }

    @Test
    @DisplayName("4.2 按版本拆分：全版本无维度 → appVersion 清单 → ≥1.5.0 的版本 inListFilter 无维度再拉一次")
    void byVersionThreeRequests() throws Exception {
        script(200, total(1000));
        script(200, versions("1.4.9", "1.5.0", "1.10.0", "(not set)", "1.5.0-beta", "1.5.1"));
        script(200, total(640));

        Ga4VersionSplit split = client("123456", credentialsB64()).activeUsersByVersion(LocalDate.of(2026, 11, 20),
                "1.5.0");

        assertThat(split).isEqualTo(new Ga4VersionSplit(1000, 640));
        assertThat(reportBodies).hasSize(3);
        assertThat(tokenCalls.get()).isEqualTo(1);
        JsonNode all = JSON.readTree(reportBodies.get(0));
        assertThat(all.at("/dateRanges/0/startDate").asText()).isEqualTo("2026-11-20");
        assertThat(all.at("/dateRanges/0/endDate").asText()).isEqualTo("2026-11-20");
        assertThat(all.at("/metrics/0/name").asText()).isEqualTo("activeUsers");
        assertThat(all.has("dimensions")).isFalse();
        assertThat(all.has("dimensionFilter")).isFalse();

        JsonNode list = JSON.readTree(reportBodies.get(1));
        assertThat(list.at("/dimensions/0/name").asText()).isEqualTo("appVersion");
        assertThat(list.at("/metrics/0/name").asText()).isEqualTo("activeUsers");
        assertThat(list.has("dimensionFilter")).isFalse();

        JsonNode filtered = JSON.readTree(reportBodies.get(2));
        assertThat(filtered.has("dimensions")).isFalse();
        assertThat(filtered.at("/dimensionFilter/filter/fieldName").asText()).isEqualTo("appVersion");
        List<String> values = new ArrayList<>();
        filtered.at("/dimensionFilter/filter/inListFilter/values").forEach(v -> values.add(v.asText()));
        assertThat(values).containsExactly("1.5.0", "1.10.0", "1.5.1");
        assertThat(filtered.at("/dateRanges/0/startDate").asText()).isEqualTo("2026-11-20");
    }

    @Test
    @DisplayName("4.2 清单里没有新版本（或当天无行）→ newVersion = 0，不发第三次请求")
    void byVersionNoNewVersionSkipsThirdRequest() throws Exception {
        script(200, total(500));
        script(200, versions("1.4.9", "1.4.10", "(not set)"));
        assertThat(client("123456", credentialsB64()).activeUsersByVersion(LocalDate.of(2026, 11, 20), "1.5.0"))
                .isEqualTo(new Ga4VersionSplit(500, 0));
        assertThat(reportBodies).hasSize(2);

        reportBodies.clear();
        script(200, "{\"rowCount\":0}");
        script(200, "{\"rowCount\":0}");
        assertThat(client("123456", credentialsB64()).activeUsersByVersion(LocalDate.of(2026, 11, 20), "1.5.0"))
                .isEqualTo(new Ga4VersionSplit(0, 0));
        assertThat(reportBodies).hasSize(2);
    }

    @Test
    @DisplayName("🔴 4.2 任一次非 200 / 结构不符 → null、不抛；未配置 → null 且不发请求")
    void byVersionFailuresAreNull() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 20);
        // 第一次就 500
        script(500, "{}");
        assertThat(client("123456", credentialsB64()).activeUsersByVersion(day, "1.5.0")).isNull();
        // 清单请求 403
        script(200, total(10));
        script(403, "{}");
        assertThat(client("123456", credentialsB64()).activeUsersByVersion(day, "1.5.0")).isNull();
        // 第三次请求 429
        script(200, total(10));
        script(200, versions("1.5.0"));
        script(429, "{}");
        assertThat(client("123456", credentialsB64()).activeUsersByVersion(day, "1.5.0")).isNull();
        // 清单结构不符（维度值缺失）
        script(200, total(10));
        script(200, "{\"rows\":[{\"metricValues\":[{\"value\":\"3\"}]}]}");
        assertThat(client("123456", credentialsB64()).activeUsersByVersion(day, "1.5.0")).isNull();
        // 全版本结构不符
        script(200, "{\"rows\":[{\"metricValues\":[{}]}]}");
        assertThat(client("123456", credentialsB64()).activeUsersByVersion(day, "1.5.0")).isNull();
        assertThat(scripted).isEmpty();

        int before = reportBodies.size();
        assertThat(client("", credentialsB64()).activeUsersByVersion(day, "1.5.0")).isNull();
        assertThat(reportBodies).hasSize(before);
    }

    @Test
    @DisplayName("4.2 401 → null 并清 token 缓存，下次重新换")
    void byVersionUnauthorizedClearsToken() throws Exception {
        Ga4ActiveUsersClient c = client("123456", credentialsB64());
        script(401, "{}");
        assertThat(c.activeUsersByVersion(LocalDate.of(2026, 11, 20), "1.5.0")).isNull();
        script(200, total(9));
        script(200, versions("1.4.0"));
        assertThat(c.activeUsersByVersion(LocalDate.of(2026, 11, 20), "1.5.0")).isEqualTo(new Ga4VersionSplit(9, 0));
        assertThat(tokenCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("4.2 版本比较：按数字逐段（1.10.0 > 1.9.9），缺段按 0，非数字段 / 空 / 解析失败视为不满足")
    void versionComparison() {
        assertThat(Ga4ActiveUsersClient.atLeast("1.5.0", "1.5.0")).isTrue();
        assertThat(Ga4ActiveUsersClient.atLeast("1.5.1", "1.5.0")).isTrue();
        assertThat(Ga4ActiveUsersClient.atLeast("1.10.0", "1.9.9")).isTrue();
        assertThat(Ga4ActiveUsersClient.atLeast("1.10.0", "1.5.0")).isTrue();
        assertThat(Ga4ActiveUsersClient.atLeast("2.0", "1.5.0")).isTrue();
        assertThat(Ga4ActiveUsersClient.atLeast("1.5", "1.5.0")).isTrue();
        assertThat(Ga4ActiveUsersClient.atLeast("1.9.9", "1.10.0")).isFalse();
        assertThat(Ga4ActiveUsersClient.atLeast("1.4.99", "1.5.0")).isFalse();
        assertThat(Ga4ActiveUsersClient.atLeast("1.4", "1.5.0")).isFalse();
        assertThat(Ga4ActiveUsersClient.atLeast("(not set)", "1.5.0")).isFalse();
        assertThat(Ga4ActiveUsersClient.atLeast("1.5.0-beta", "1.5.0")).isFalse();
        assertThat(Ga4ActiveUsersClient.atLeast("1.5.0+12", "1.5.0")).isFalse();
        assertThat(Ga4ActiveUsersClient.atLeast("1..5", "1.5.0")).isFalse();
        assertThat(Ga4ActiveUsersClient.atLeast("", "1.5.0")).isFalse();
        assertThat(Ga4ActiveUsersClient.atLeast(null, "1.5.0")).isFalse();
        assertThat(Ga4ActiveUsersClient.atLeast("99999999999.0", "1.5.0")).isFalse();
        assertThat(Ga4ActiveUsersClient.atLeast("1.6.0", "abc")).isFalse();
    }
}

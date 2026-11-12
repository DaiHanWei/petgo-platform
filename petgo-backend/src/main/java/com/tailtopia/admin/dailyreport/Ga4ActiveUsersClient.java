package com.tailtopia.admin.dailyreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 日报「日活（含游客）」：GA4 Data API {@code runReport} 取某天的 {@code activeUsers}
 * （Firebase 统计，iOS + Android 合计，按设备、含未登录用户）。spec-v132-ga4-dau-daily-report。
 *
 * <p>鉴权走服务账号 JWT bearer 换 access token（RS256 用 JDK 自带签名，不引 google-auth / gRPC 客户端）。
 *
 * <p>🔴 任何失败（未配置 / 密钥坏 / 换 token 失败 / 4xx·5xx / 超时 / 响应结构不符）一律返回 {@code null}
 * —— 日报该字段显示「—」照常推送，日活缺数不能拖垮整份日报。日志只记异常类型与状态码，
 * <b>绝不</b>打印 token、密钥或响应体。
 *
 * <p>日期按 GA4 <b>属性时区</b>解释 ⇒ 属性时区必须设为雅加达，才与日报的 WIB 自然日对齐。
 */
@Component
public class Ga4ActiveUsersClient {

    private static final Logger log = LoggerFactory.getLogger(Ga4ActiveUsersClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String SCOPE = "https://www.googleapis.com/auth/analytics.readonly";
    static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
    static final String DEFAULT_API_BASE = "https://analyticsdata.googleapis.com";

    private final DailyReportProperties props;
    private final HttpClient http;
    private final Clock clock;
    private final String apiBase;

    private String cachedToken;
    private Instant cachedTokenExpiry = Instant.EPOCH;

    // 🔴 两个构造器必须标明 Spring 用哪个（DailyReportWiringTest 守着，2026-09-25 stag 启动即崩的回归）
    @Autowired
    public Ga4ActiveUsersClient(DailyReportProperties props) {
        this(props, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds())).build(),
                Clock.systemUTC(), DEFAULT_API_BASE);
    }

    Ga4ActiveUsersClient(DailyReportProperties props, HttpClient http, Clock clock, String apiBase) {
        this.props = props;
        this.http = http;
        this.clock = clock;
        this.apiBase = apiBase;
    }

    /** 某天（GA4 属性时区的自然日）的活跃用户数；未配置或任何失败 → {@code null}。 */
    public Long activeUsers(LocalDate day) {
        DailyReportProperties.Ga4 cfg = props.getGa4();
        if (!cfg.isEnabled()) {
            return null;
        }
        try {
            String resp = runReport(cfg, reportBody(day, null, null));
            return resp == null ? null : parseActiveUsers(resp);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("ga4 runReport interrupted");
            return null;
        } catch (Exception e) {
            log.warn("ga4 runReport failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** GA4 是否已配置（property-id 与服务账号密钥都非空）。让调用方区分「未配置」与「拉取失败」（两者取数方法都返回 null）。 */
    public boolean isConfigured() {
        return props.getGa4().isEnabled();
    }

    /**
     * 某天按 App 版本拆分的活跃用户（analytics-pipeline Story 4.2，架构 D-11）。
     *
     * <p>三次 {@code runReport}：①无维度 → 全版本 {@code all}；②{@code dimensions=[appVersion]} → 当天出现过的版本清单；
     * ③把清单里 ≥ {@code minVersion}（按数字逐段比较，见 {@link #atLeast}）的版本放进 {@code inListFilter}、无维度再拉一次
     * → {@code newVersion}（GA4 侧已按设备去重，同一设备当天用过两个新版本只算一次）。清单里没有新版本 → {@code newVersion = 0}，
     * 不发第三次请求。
     *
     * @return 未配置或任何一次请求失败 / 响应结构不符 → {@code null}（与 {@link #activeUsers} 同一失败语义）
     */
    public Ga4VersionSplit activeUsersByVersion(LocalDate day, String minVersion) {
        DailyReportProperties.Ga4 cfg = props.getGa4();
        if (!cfg.isEnabled()) {
            return null;
        }
        try {
            String allResp = runReport(cfg, reportBody(day, null, null));
            if (allResp == null) {
                return null;
            }
            long all = parseActiveUsers(allResp);
            String listResp = runReport(cfg, reportBody(day, "appVersion", null));
            if (listResp == null) {
                return null;
            }
            List<String> newVersions = new ArrayList<>();
            for (String v : parseDimensionValues(listResp)) {
                if (atLeast(v, minVersion)) {
                    newVersions.add(v);
                }
            }
            if (newVersions.isEmpty()) {
                return new Ga4VersionSplit(all, 0);
            }
            String newResp = runReport(cfg, reportBody(day, null, newVersions));
            if (newResp == null) {
                return null;
            }
            return new Ga4VersionSplit(all, parseActiveUsers(newResp));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("ga4 runReport by version interrupted");
            return null;
        } catch (Exception e) {
            log.warn("ga4 runReport by version failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 按版本拆分的 GA4 日活。
     *
     * @param all        全版本 activeUsers
     * @param newVersion appVersion ≥ 阈值版本的 activeUsers（GA4 侧去重）
     */
    public record Ga4VersionSplit(long all, long newVersion) {
    }

    /**
     * runReport 请求体：单日、{@code metrics=[activeUsers]}；{@code dimension} 非空时按该维度拆行；
     * {@code appVersions} 非空时加 {@code appVersion inListFilter}。
     */
    static Map<String, Object> reportBody(LocalDate day, String dimension, List<String> appVersions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dateRanges", List.of(Map.of("startDate", day.toString(), "endDate", day.toString())));
        if (dimension != null) {
            body.put("dimensions", List.of(Map.of("name", dimension)));
        }
        body.put("metrics", List.of(Map.of("name", "activeUsers")));
        if (appVersions != null) {
            body.put("dimensionFilter", Map.of("filter", Map.of(
                    "fieldName", "appVersion",
                    "inListFilter", Map.of("values", List.copyOf(appVersions)))));
        }
        return body;
    }

    /**
     * 发一次 runReport。非 200 → 记状态码返回 {@code null}（401 顺带清 token 缓存）；网络 / 鉴权异常向上抛，由调用方转 null。
     * 🔴 不打印 token、密钥或响应体。
     */
    private String runReport(DailyReportProperties.Ga4 cfg, Map<String, Object> body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(
                        apiBase + "/v1beta/properties/" + cfg.getPropertyId().trim() + ":runReport"))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + accessToken(cfg))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            if (resp.statusCode() == 401) {
                clearToken(); // token 被吊销 / 密钥轮换：下次重新换，不必等缓存自然过期
            }
            log.warn("ga4 runReport failed: HTTP {}", resp.statusCode());
            return null;
        }
        return resp.body();
    }

    /** 按维度拆行的 runReport 响应 → 每行第一个维度值。无 rows = 空清单；结构不符抛异常（由调用方转 null）。 */
    static List<String> parseDimensionValues(String body) throws Exception {
        JsonNode rows = JSON.readTree(body).path("rows");
        List<String> out = new ArrayList<>();
        if (rows.isMissingNode() || rows.isEmpty()) {
            return out;
        }
        if (!rows.isArray()) {
            throw new IllegalStateException("unexpected runReport shape");
        }
        for (JsonNode row : rows) {
            JsonNode value = row.path("dimensionValues").path(0).path("value");
            if (!value.isTextual()) {
                throw new IllegalStateException("unexpected runReport shape");
            }
            out.add(value.asText());
        }
        return out;
    }

    /**
     * {@code version ≥ min}：按 {@code .} 分段逐段按数字比较（{@code 1.10.0 > 1.9.9}），缺的段按 0（{@code 1.5 = 1.5.0}）。
     * 任一方含非数字段（如 {@code (not set)}、{@code 1.5.0-beta}、{@code 1.5.0+12}）或为空 → {@code false}（视为不满足）。
     */
    static boolean atLeast(String version, String min) {
        int[] v = parseVersion(version);
        int[] m = parseVersion(min);
        if (v == null || m == null) {
            return false;
        }
        for (int i = 0; i < Math.max(v.length, m.length); i++) {
            int a = i < v.length ? v[i] : 0;
            int b = i < m.length ? m[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return true;
    }

    /** {@code 1.5.0} → {@code [1, 5, 0]}；非纯数字段、空段、超长 → null。 */
    static int[] parseVersion(String version) {
        if (version == null || version.isBlank() || version.length() > 64) {
            return null;
        }
        String[] parts = version.strip().split("\\.", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 9 || !p.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return null;
            }
            out[i] = Integer.parseInt(p);
        }
        return out;
    }

    /** runReport 响应 → activeUsers。无 rows（当天无数据）= 0；结构不符抛异常（由调用方转 null）。 */
    static long parseActiveUsers(String body) throws Exception {
        JsonNode rows = JSON.readTree(body).path("rows");
        if (rows.isMissingNode() || rows.isEmpty()) {
            return 0;
        }
        JsonNode value = rows.get(0).path("metricValues").path(0).path("value");
        if (!value.isTextual() && !value.isNumber()) {
            throw new IllegalStateException("unexpected runReport shape");
        }
        return Long.parseLong(value.asText());
    }

    private synchronized void clearToken() {
        cachedToken = null;
        cachedTokenExpiry = Instant.EPOCH;
    }

    /** 服务账号 JWT bearer 换 access token；有效期内复用（提前 60s 刷新）。 */
    private synchronized String accessToken(DailyReportProperties.Ga4 cfg) throws Exception {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(cachedTokenExpiry.minusSeconds(60))) {
            return cachedToken;
        }
        JsonNode key = JSON.readTree(Base64.getDecoder().decode(cfg.getCredentialsB64().trim()));
        String tokenUri = key.path("token_uri").asText(DEFAULT_TOKEN_URI);
        String assertion = jwt(key.path("client_email").asText(), tokenUri,
                privateKey(key.path("private_key").asText()), now);

        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("token endpoint HTTP " + resp.statusCode());
        }
        JsonNode tok = JSON.readTree(resp.body());
        String token = tok.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("token endpoint returned no access_token");
        }
        cachedToken = token;
        cachedTokenExpiry = now.plusSeconds(tok.path("expires_in").asLong(3600));
        return token;
    }

    /** RS256 JWT：iss=服务账号邮箱，aud=token_uri，scope=analytics.readonly，有效 1 小时。 */
    static String jwt(String clientEmail, String tokenUri, PrivateKey key, Instant now) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String claims = b64.encodeToString(JSON.writeValueAsBytes(Map.of(
                "iss", clientEmail,
                "scope", SCOPE,
                "aud", tokenUri,
                "iat", now.getEpochSecond(),
                "exp", now.getEpochSecond() + 3600)));
        String signingInput = header + "." + claims;
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64.encodeToString(sig.sign());
    }

    /** 服务账号 JSON 里的 {@code private_key}（PKCS#8 PEM）→ PrivateKey。 */
    static PrivateKey privateKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }
}

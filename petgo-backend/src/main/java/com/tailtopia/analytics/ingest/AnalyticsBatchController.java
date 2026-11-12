package com.tailtopia.analytics.ingest;

import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.config.AnalyticsStorageProperties;
import com.tailtopia.analytics.identity.IdentityResolver;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import com.tailtopia.shared.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 埋点批量接收 {@code POST /api/v1/analytics/batch}（analytics-pipeline Story 1.4，FR-7 / FR-8 / FR-10）。可不带 JWT。
 *
 * <h2>判定顺序（定死，Story 2.1 的 App 端按返回码决定保留 / 丢弃）</h2>
 * <ol>
 *   <li>体积：{@code Content-Length} &gt; 500 KB 直接 413；无长度（chunked）边读边计数，读到上限 +1 字节即停并 413
 *       —— 超大请求不会先被整体读进内存。</li>
 *   <li>解析（Jackson 3 树）：不是 JSON / 根不是对象 / {@code batch} 不是数组 / {@code batch} 为空 → 400；
 *       条数 &gt; 500 → 413；任一条重新序列化后 &gt; 32 KB → 整批 413。</li>
 *   <li>管道状态：停用（含 {@link Reason#CATALOG_INVALID}：清单坏了无法判 {@code unknown_event}）/ 停机中 → 503 + {@code Retry-After}。</li>
 *   <li>停写熔断（Story 1.10，{@link IngestQueue#isWriteStopped()}，埋点库超过 {@code analytics.storage.stop-bytes}）→ 429 +
 *       {@code Retry-After}（{@code analytics.storage.breaker-retry-after-seconds}，默认 300）。放在限流之前：熔断期间不消耗限流配额、
 *       不做校验。入队时若恰好刚熔断（{@code offerAll} 被停写门拒）同样按熔断 429 返回。</li>
 *   <li>限流（逐条校验之前，用请求体里的原始 {@code anonymousId}）：IP 兜底 + 每个不同匿名 id 各计一次 → 429 + {@code Retry-After}。</li>
 *   <li>逐条校验（{@link IngestValidator}）→ 身份判定（{@link IdentityResolver}）→ 整批入队，满了 429 + {@code Retry-After}。</li>
 *   <li>202 {@code {accepted, rejected}}。</li>
 * </ol>
 *
 * <h2>🔴 隐私 / 日志</h2>
 * IP 只以 {@code sha256} 前 16 位 hex 进 Redis 限流键（60 秒过期），不进表、不进日志；本接口已从
 * {@code ApiAccessLoggingFilter} 排除；本类日志只打条数、异常类名，不打匿名 id、用户标识、属性、上下文、IP、JWT。
 *
 * <p>无效 / 过期 JWT 会被资源服务器过滤器在授权前 401（全仓现有行为，permitAll 拦不住），不会走到这里。
 */
@RestController
public class AnalyticsBatchController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsBatchController.class);

    static final String PATH = "/api/v1/analytics/batch";
    /** 管道停用 / 停机中时 503 的 {@code Retry-After}（秒）。App 对 5xx 自己指数退避，这里只给个下限。 */
    static final int UNAVAILABLE_RETRY_AFTER_SECONDS = 60;
    static final String RL_AID_PREFIX = "rl:analytics:aid:";
    static final String RL_IP_PREFIX = "rl:analytics:ip:";
    /** 原始 anonymousId 超过此长度不拿来做限流键（必然校验不过，也不让超长串进 Redis 键）。 */
    private static final int MAX_RL_ID_LENGTH = 64;

    /** 本接口自用的 Jackson 3 实例：只做树解析 / 序列化，不受全局 mapper 配置影响。 */
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** 202 响应体。字段名是 Story 2.1 的契约，不要改。 */
    public record BatchResponse(int accepted, int rejected) {
    }

    private final IngestQueue queue;
    private final EventCatalog catalog;
    private final IdentityResolver identity;
    private final AnalyticsPipelineState state;
    private final RedisRateLimiter rateLimiter;
    private final IngestProperties props;
    private final int breakerRetryAfterSeconds;
    private final Clock clock;

    @Autowired
    public AnalyticsBatchController(IngestQueue queue, EventCatalog catalog, IdentityResolver identity,
            AnalyticsPipelineState state, RedisRateLimiter rateLimiter, IngestProperties props,
            AnalyticsStorageProperties storage) {
        this(queue, catalog, identity, state, rateLimiter, props, storage.breakerRetryAfterSeconds(),
                Clock.systemUTC());
    }

    /** 测试用：熔断 {@code Retry-After} 取默认值。 */
    AnalyticsBatchController(IngestQueue queue, EventCatalog catalog, IdentityResolver identity,
            AnalyticsPipelineState state, RedisRateLimiter rateLimiter, IngestProperties props, Clock clock) {
        this(queue, catalog, identity, state, rateLimiter, props,
                AnalyticsStorageProperties.defaults().breakerRetryAfterSeconds(), clock);
    }

    AnalyticsBatchController(IngestQueue queue, EventCatalog catalog, IdentityResolver identity,
            AnalyticsPipelineState state, RedisRateLimiter rateLimiter, IngestProperties props,
            int breakerRetryAfterSeconds, Clock clock) {
        this.queue = queue;
        this.catalog = catalog;
        this.identity = identity;
        this.state = state;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.breakerRetryAfterSeconds = breakerRetryAfterSeconds;
        this.clock = clock;
    }

    @PostMapping(PATH)
    public ResponseEntity<?> batch(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt) throws IOException {
        // 同批同值：入口取一次服务端时间
        Instant receivedAt = clock.instant();

        // 1. 体积
        byte[] body = readBounded(request, props.maxRequestBytes());
        if (body == null) {
            return problem(HttpStatus.PAYLOAD_TOO_LARGE, ErrorTypes.VALIDATION, "Payload Too Large",
                    "请求体超过 " + props.maxRequestBytes() + " 字节");
        }

        // 2. 解析
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            return badRequest("请求体不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            return badRequest("请求体根必须是 JSON 对象");
        }
        JsonNode batch = root.get("batch");
        if (batch == null || !batch.isArray()) {
            return badRequest("batch 必须是数组");
        }
        if (batch.isEmpty()) {
            return badRequest("batch 不能为空");
        }
        if (batch.size() > props.maxEventsPerBatch()) {
            return problem(HttpStatus.PAYLOAD_TOO_LARGE, ErrorTypes.VALIDATION, "Payload Too Large",
                    "batch 条数超过 " + props.maxEventsPerBatch());
        }
        for (JsonNode element : batch.values()) {
            if (JSON.writeValueAsBytes(element).length > props.maxEventBytes()) {
                return problem(HttpStatus.PAYLOAD_TOO_LARGE, ErrorTypes.VALIDATION, "Payload Too Large",
                        "单条事件超过 " + props.maxEventBytes() + " 字节");
            }
        }

        // 3. 管道状态（停用 / 清单无效 / 停机中 → 503）
        if (!state.isEnabled() || queue.isClosed()) {
            return unavailable();
        }

        // 3b. 停写熔断（Story 1.10）→ 429
        if (queue.isWriteStopped()) {
            return writeStopped();
        }

        // 4. 限流
        Long retryAfter = rateLimited(request, batch);
        if (retryAfter != null) {
            return tooManyRequests(retryAfter, "上报过于频繁，请稍后再试");
        }

        // 5. 逐条校验 → 身份 → 入队
        IngestValidator.Result validated = IngestValidator.validate(batch.values(), receivedAt, catalog::isRegistered);
        List<RawEvent> events = identity.resolve(validated.accepted(), jwt);
        if (!queue.offerAll(events)) {
            if (queue.isClosed()) {
                return unavailable();
            }
            if (queue.isWriteStopped()) {
                return writeStopped();
            }
            log.warn("analytics ingest queue full: rejectedBatch={} queueBytes={}", events.size(),
                    queue.currentBytes());
            return tooManyRequests(props.queueFullRetryAfterSeconds(), "埋点队列已满，请稍后再试");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new BatchResponse(events.size(), validated.rejected()));
    }

    /**
     * 有界读取请求体：{@code Content-Length} 已知且超限 → 直接 {@code null}；否则边读边计数，超过 {@code max} 即停、返回 {@code null}。
     */
    static byte[] readBounded(HttpServletRequest request, int max) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > max) {
            return null;
        }
        InputStream in = request.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream(declared > 0 ? (int) declared : 8192);
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf, 0, (int) Math.min(buf.length, max + 1L - total))) > 0) {
            total += n;
            if (total > max) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 限流判定。IP 兜底先算（一次 Redis 往返），再按批次里每个不同的原始 {@code anonymousId} 各计一次。
     * Redis 异常 → 放行（埋点不能因 Redis 抖动 500）。
     *
     * @return 超限时的 {@code Retry-After} 秒数；放行返回 {@code null}
     */
    private Long rateLimited(HttpServletRequest request, JsonNode batch) {
        IngestProperties.RateLimit rl = props.rateLimit();
        Duration window = Duration.ofSeconds(Math.max(1, rl.windowSeconds()));
        try {
            String ipKey = RL_IP_PREFIX + ipHash(ClientIp.of(request));
            if (!rateLimiter.tryAcquire(ipKey, rl.perIp(), window)) {
                return rateLimiter.secondsUntilReset(ipKey, window);
            }
            Long worst = null;
            for (String aid : rawAnonymousIds(batch)) {
                String key = RL_AID_PREFIX + aid;
                if (!rateLimiter.tryAcquire(key, rl.perDevice(), window)) {
                    long s = rateLimiter.secondsUntilReset(key, window);
                    worst = worst == null ? s : Math.max(worst, s);
                }
            }
            return worst;
        } catch (RuntimeException e) {
            log.warn("analytics ingest rate limit unavailable, allowing: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** 批次里不同的原始 {@code anonymousId}（非空字符串、长度合理，小写去重）；取不到的只走 IP 兜底。 */
    static Set<String> rawAnonymousIds(JsonNode batch) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode element : batch.values()) {
            JsonNode v = element == null ? null : element.get("anonymousId");
            if (v != null && v.isString()) {
                String s = v.stringValue().trim();
                if (!s.isEmpty() && s.length() <= MAX_RL_ID_LENGTH) {
                    ids.add(s.toLowerCase(Locale.ROOT));
                }
            }
        }
        return ids;
    }

    /** IP → sha256 前 16 位 hex。原值不留存、不打日志。 */
    static String ipHash(String ip) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<ProblemDetail> badRequest(String detail) {
        return problem(HttpStatus.BAD_REQUEST, ErrorTypes.VALIDATION, "Validation Failed", detail);
    }

    private static ResponseEntity<ProblemDetail> tooManyRequests(long retryAfterSeconds, String detail) {
        ProblemDetail pd = body(HttpStatus.TOO_MANY_REQUESTS, ErrorTypes.RATE_LIMITED, "Rate Limited", detail);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, retryAfterSeconds)))
                .body(pd);
    }

    /** 停写熔断：与队列满同一套 429 构造，{@code Retry-After} 取熔断配置。 */
    private ResponseEntity<ProblemDetail> writeStopped() {
        return tooManyRequests(breakerRetryAfterSeconds, "埋点存储已达停写线，请稍后再试");
    }

    private static ResponseEntity<ProblemDetail> unavailable() {
        ProblemDetail pd = body(HttpStatus.SERVICE_UNAVAILABLE, ErrorTypes.INTERNAL, "Service Unavailable",
                "埋点接收暂不可用，请稍后重试");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(UNAVAILABLE_RETRY_AFTER_SECONDS))
                .body(pd);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, URI type, String title,
            String detail) {
        return ResponseEntity.status(status).body(body(status, type, title, detail));
    }

    /** 与 {@code GlobalExceptionHandler#base} 同形：type / title / status / detail / instance / traceId。 */
    private static ProblemDetail body(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(type);
        pd.setTitle(title);
        pd.setInstance(URI.create(PATH));
        String traceId = MDC.get("traceId");
        pd.setProperty("traceId", traceId != null ? traceId : UUID.randomUUID().toString());
        return pd;
    }
}

package com.tailtopia.analytics.ingest;

import com.tailtopia.analytics.config.ConfigValues;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 埋点接收配置 {@code analytics.ingest.*}（analytics-pipeline Story 1.4），全部有默认值。
 *
 * <p>🔴 <b>配置非法不阻断后端启动</b>（Story 4.9 AC11，写法同 4-2 的 {@code Ga4SnapshotProperties}）：全部键按字符串绑定、自行解析。
 * 接收是埋点的入口，没有可以单独停用的子功能，所以非法键<b>回退该键的默认值</b>、接收照常；{@link #invalidReason()} 只含键名，
 * 启动时打 WARN，{@code AnalyticsConfigAlert} 在应用就绪时发 TECH 告警。原 {@code record} 的 {@code int} / {@code Duration}
 * 类型绑定失败会让整个后端起不来。
 *
 * <ul>
 *   <li>{@code max-request-bytes}：单个请求体上限（A6：500 KB），超限 413</li>
 *   <li>{@code max-event-bytes}：单条事件序列化后上限（A6：32 KB），任一条超限整批 413</li>
 *   <li>{@code max-events-per-batch}：单批条数上限，超限 413</li>
 *   <li>{@code queue-max-bytes}：内存队列按估算字节的上限（D2 破例，默认 64 MB），满了 429</li>
 *   <li>{@code queue-full-retry-after-seconds}：队列满时 429 的 {@code Retry-After}（秒）</li>
 *   <li>{@code flush-batch-size} / {@code flush-interval-ms}：写库线程每批最多条数 / 攒批最长等待（毫秒）</li>
 *   <li>{@code shutdown-drain-timeout}：优雅停机时把队列写完的最长等待（默认 20s）</li>
 *   <li>{@code watchdog-interval}：写库线程看门狗检查间隔（Story 4.9，默认 60s；纯数字按秒）</li>
 *   <li>{@code stall-threshold}：写库线程多久没有 drain 返回算停滞（Story 4.9，默认 5m；纯数字按秒）</li>
 *   <li>{@code rate-limit.*}：限流阈值</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "analytics.ingest")
public final class IngestProperties {

    private static final Logger log = LoggerFactory.getLogger(IngestProperties.class);

    static final int DEFAULT_MAX_REQUEST_BYTES = 512_000;
    static final int DEFAULT_MAX_EVENT_BYTES = 32_768;
    static final int DEFAULT_MAX_EVENTS_PER_BATCH = 500;
    static final long DEFAULT_QUEUE_MAX_BYTES = 67_108_864L;
    static final int DEFAULT_QUEUE_FULL_RETRY_AFTER = 30;
    static final int DEFAULT_FLUSH_BATCH_SIZE = 500;
    static final long DEFAULT_FLUSH_INTERVAL_MS = 1000;
    static final Duration DEFAULT_SHUTDOWN_DRAIN_TIMEOUT = Duration.ofSeconds(20);
    static final Duration DEFAULT_WATCHDOG_INTERVAL = Duration.ofSeconds(60);
    static final Duration DEFAULT_STALL_THRESHOLD = Duration.ofMinutes(5);
    static final int DEFAULT_PER_DEVICE = 120;
    static final int DEFAULT_PER_IP = 3000;
    static final int DEFAULT_WINDOW_SECONDS = 60;

    private final int maxRequestBytes;
    private final int maxEventBytes;
    private final int maxEventsPerBatch;
    private final long queueMaxBytes;
    private final int queueFullRetryAfterSeconds;
    private final int flushBatchSize;
    private final long flushIntervalMs;
    private final Duration shutdownDrainTimeout;
    private final Duration watchdogInterval;
    private final Duration stallThreshold;
    private final RateLimit rateLimit;
    /** 非 null = 有键非法（已回退默认值），只含键名。 */
    private final String invalidReason;

    /** Spring 绑定用：按字符串接收，非法值不抛、回退该键默认值。 */
    @ConstructorBinding
    public IngestProperties(@DefaultValue("512000") String maxRequestBytes, @DefaultValue("32768") String maxEventBytes,
            @DefaultValue("500") String maxEventsPerBatch, @DefaultValue("67108864") String queueMaxBytes,
            @DefaultValue("30") String queueFullRetryAfterSeconds, @DefaultValue("500") String flushBatchSize,
            @DefaultValue("1000") String flushIntervalMs, @DefaultValue("20s") String shutdownDrainTimeout,
            @DefaultValue("60s") String watchdogInterval, @DefaultValue("5m") String stallThreshold,
            @DefaultValue RateLimit rateLimit) {
        Reasons r = new Reasons();
        this.maxRequestBytes = r.positiveInt(maxRequestBytes, "max-request-bytes", DEFAULT_MAX_REQUEST_BYTES);
        this.maxEventBytes = r.positiveInt(maxEventBytes, "max-event-bytes", DEFAULT_MAX_EVENT_BYTES);
        this.maxEventsPerBatch = r.positiveInt(maxEventsPerBatch, "max-events-per-batch", DEFAULT_MAX_EVENTS_PER_BATCH);
        Long q = ConfigValues.positiveLong(queueMaxBytes);
        if (q == null) {
            r.add("queue-max-bytes 须为正整数");
        }
        this.queueMaxBytes = q == null ? DEFAULT_QUEUE_MAX_BYTES : q;
        this.queueFullRetryAfterSeconds = r.positiveInt(queueFullRetryAfterSeconds, "queue-full-retry-after-seconds",
                DEFAULT_QUEUE_FULL_RETRY_AFTER);
        this.flushBatchSize = r.positiveInt(flushBatchSize, "flush-batch-size", DEFAULT_FLUSH_BATCH_SIZE);
        Long f = ConfigValues.positiveLong(flushIntervalMs);
        if (f == null) {
            r.add("flush-interval-ms 须为正整数（毫秒）");
        }
        this.flushIntervalMs = f == null ? DEFAULT_FLUSH_INTERVAL_MS : f;
        // shutdown-drain-timeout 沿用原 Duration 绑定口径（纯数字 = 毫秒）；Story 4.9 新增的两个键纯数字按秒（键名不带 -ms）
        this.shutdownDrainTimeout = r.duration(shutdownDrainTimeout, "shutdown-drain-timeout", ChronoUnit.MILLIS,
                DEFAULT_SHUTDOWN_DRAIN_TIMEOUT);
        this.watchdogInterval = r.duration(watchdogInterval, "watchdog-interval", ChronoUnit.SECONDS,
                DEFAULT_WATCHDOG_INTERVAL);
        this.stallThreshold = r.duration(stallThreshold, "stall-threshold", ChronoUnit.SECONDS,
                DEFAULT_STALL_THRESHOLD);
        this.rateLimit = rateLimit == null ? RateLimit.defaults() : rateLimit;
        this.invalidReason = ConfigValues.join(r.reason, this.rateLimit.invalidReason());
        if (r.reason != null) {
            log.warn("analytics ingest config invalid, using defaults: ({})", r.reason);
        }
    }

    /** 收集非法键名（前缀 {@code analytics.ingest.}）。 */
    private static final class Reasons {
        String reason;

        void add(String what) {
            reason = ConfigValues.join(reason, "analytics.ingest." + what);
        }

        int positiveInt(String raw, String key, int def) {
            Integer v = ConfigValues.positiveInt(raw);
            if (v == null) {
                add(key + " 须为正整数");
                return def;
            }
            return v;
        }

        Duration duration(String raw, String key, ChronoUnit bareUnit, Duration def) {
            Duration d = ConfigValues.duration(raw, bareUnit, false);
            if (d == null) {
                add(key + " 须为正的时长（例：20s）");
                return def;
            }
            return d;
        }
    }

    /**
     * 限流阈值：以匿名 id 为主（{@code perDevice}），IP 只做很宽的兜底（{@code perIp}，印尼移动网络 CGNAT 共用出口）。
     * 非法键同样回退默认。
     */
    public static final class RateLimit {

        private final int perDevice;
        private final int perIp;
        private final int windowSeconds;
        private final String invalidReason;

        /**
         * @param perDevice     每个 {@code anonymousId} 每窗口最多请求数
         * @param perIp         每个 IP（哈希后）每窗口最多请求数
         * @param windowSeconds 固定窗口长度（秒）
         */
        @ConstructorBinding
        public RateLimit(@DefaultValue("120") String perDevice, @DefaultValue("3000") String perIp,
                @DefaultValue("60") String windowSeconds) {
            Reasons r = new Reasons();
            this.perDevice = r.positiveInt(perDevice, "rate-limit.per-device", DEFAULT_PER_DEVICE);
            this.perIp = r.positiveInt(perIp, "rate-limit.per-ip", DEFAULT_PER_IP);
            this.windowSeconds = r.positiveInt(windowSeconds, "rate-limit.window-seconds", DEFAULT_WINDOW_SECONDS);
            String reason = r.reason;
            this.invalidReason = reason;
            if (reason != null) {
                log.warn("analytics ingest rate limit config invalid, using defaults: ({})", reason);
            }
        }

        public RateLimit(int perDevice, int perIp, int windowSeconds) {
            this(String.valueOf(perDevice), String.valueOf(perIp), String.valueOf(windowSeconds));
        }

        static RateLimit defaults() {
            return new RateLimit(DEFAULT_PER_DEVICE, DEFAULT_PER_IP, DEFAULT_WINDOW_SECONDS);
        }

        public int perDevice() {
            return perDevice;
        }

        public int perIp() {
            return perIp;
        }

        public int windowSeconds() {
            return windowSeconds;
        }

        String invalidReason() {
            return invalidReason;
        }
    }

    /** 全默认值（测试用）。 */
    static IngestProperties defaults() {
        return new IngestProperties("512000", "32768", "500", "67108864", "30", "500", "1000", "20s", "60s", "5m",
                RateLimit.defaults());
    }

    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    public int maxEventBytes() {
        return maxEventBytes;
    }

    public int maxEventsPerBatch() {
        return maxEventsPerBatch;
    }

    public long queueMaxBytes() {
        return queueMaxBytes;
    }

    public int queueFullRetryAfterSeconds() {
        return queueFullRetryAfterSeconds;
    }

    public int flushBatchSize() {
        return flushBatchSize;
    }

    public long flushIntervalMs() {
        return flushIntervalMs;
    }

    public Duration shutdownDrainTimeout() {
        return shutdownDrainTimeout;
    }

    /** 写库线程看门狗检查间隔。 */
    public Duration watchdogInterval() {
        return watchdogInterval;
    }

    /** 写库线程停滞判定阈值。 */
    public Duration stallThreshold() {
        return stallThreshold;
    }

    public RateLimit rateLimit() {
        return rateLimit;
    }

    /** 非法键的原因（只含键名，已回退默认值）；全部合法 = null。 */
    public String invalidReason() {
        return invalidReason;
    }
}

package com.tailtopia.analytics.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 埋点库存储水位与停写熔断配置 {@code analytics.storage.*}（analytics-pipeline Story 1.10，FR-16 / 架构「磁盘熔断」），全部有默认值。
 *
 * <p>字节阈值可写 {@code 40GB}（Spring 的 GB = 1024³ 字节，与 {@code pg_size_pretty} 同口径），也可写纯数字（字节；L1 测试配成
 * {@code 1} 即 1 字节）。
 *
 * <p>🔴 <b>配置非法不阻断后端启动</b>（Story 4.9 AC10，写法同 4-2 的 {@code Ga4SnapshotProperties}）：全部键按字符串绑定、自行解析。
 * 任一键非法 → 该键回退默认值、{@link #valid()} 为 false；其中<b>熔断相关键</b>（{@code stop-bytes} / {@code breaker-*}）非法时
 * 只停用「按水位停收」（{@link #breakerEnabled()} 为 false，{@code IngestCircuitBreaker} 不再打开，接收照常），其余键非法不影响熔断；
 * 水位记录与水位告警按（回退后的）值照常跑；启动时打 WARN，{@code AnalyticsConfigAlert} 在应用就绪时发 TECH 告警。
 * {@link #invalidReason()} 只含键名、不含值。原实现在紧凑构造器里抛 / 类型绑定失败，会让整个后端起不来。
 *
 * <ul>
 *   <li>{@code alert-bytes}：超过即 TECH 告警（默认 40 GB）</li>
 *   <li>{@code stop-bytes}：超过即接收端停写 429（默认 50 GB），降回到此值及以下自动恢复</li>
 *   <li>{@code month-events-hint}：本 WIB 自然月事件量达到此值提示「评估切回第三方」（默认 6,000 万）</li>
 *   <li>{@code hint-months}：按月增速外推将在此月数内触及 {@code alert-bytes} 时提示「评估切回第三方」（默认 3）</li>
 *   <li>{@code breaker-check-ms} / {@code breaker-initial-delay-ms}：熔断检查间隔 / 首延（默认 5 分钟 / 0；纯数字按毫秒）</li>
 *   <li>{@code breaker-retry-after-seconds}：熔断时 429 的 {@code Retry-After}（秒，默认 300，与检查周期一致）</li>
 *   <li>{@code watermark-cron}：水位每日任务 cron（WIB，默认 {@code 0 30 4 * * *}；{@code "-"} = 不注册；非法 → 回退默认）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "analytics.storage")
public final class AnalyticsStorageProperties {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsStorageProperties.class);

    static final DataSize DEFAULT_ALERT = DataSize.ofGigabytes(40);
    static final DataSize DEFAULT_STOP = DataSize.ofGigabytes(50);
    static final long DEFAULT_MONTH_EVENTS_HINT = 60_000_000L;
    static final int DEFAULT_HINT_MONTHS = 3;
    static final long DEFAULT_BREAKER_CHECK_MS = 300_000L;
    static final int DEFAULT_RETRY_AFTER = 300;
    public static final String DEFAULT_WATERMARK_CRON = "0 30 4 * * *";

    private final DataSize alertBytes;
    private final DataSize stopBytes;
    private final long monthEventsHint;
    private final int hintMonths;
    private final long breakerCheckMs;
    private final Duration breakerInitialDelay;
    private final int breakerRetryAfterSeconds;
    private final String watermarkCron;
    /** 非 null = 有键非法（已回退默认），只含键名。 */
    private final String invalidReason;
    /** 非 null = 熔断相关键（stop-bytes / breaker-*）非法 → 已停用按水位停收。 */
    private final String breakerInvalidReason;

    /** Spring 绑定用：按字符串接收，非法值不抛、回退默认并停用按水位停收。 */
    @ConstructorBinding
    public AnalyticsStorageProperties(@DefaultValue("40GB") String alertBytes, @DefaultValue("50GB") String stopBytes,
            @DefaultValue("60000000") String monthEventsHint, @DefaultValue("3") String hintMonths,
            @DefaultValue("300000") String breakerCheckMs, @DefaultValue("0") String breakerInitialDelayMs,
            @DefaultValue("300") String breakerRetryAfterSeconds,
            @DefaultValue(DEFAULT_WATERMARK_CRON) String watermarkCron) {
        String reason = null;
        String breakerReason = null;
        DataSize alert = ConfigValues.positiveDataSize(alertBytes);
        if (alert == null) {
            reason = ConfigValues.join(reason, "analytics.storage.alert-bytes 须为正的字节量（例：40GB）");
        }
        DataSize stop = ConfigValues.positiveDataSize(stopBytes);
        if (stop == null) {
            breakerReason = ConfigValues.join(breakerReason, "analytics.storage.stop-bytes 须为正的字节量（例：50GB）");
        }
        Long hint = ConfigValues.positiveLong(monthEventsHint);
        if (hint == null) {
            reason = ConfigValues.join(reason, "analytics.storage.month-events-hint 须为正整数");
        }
        Integer months = ConfigValues.positiveInt(hintMonths);
        if (months == null) {
            reason = ConfigValues.join(reason, "analytics.storage.hint-months 须为正整数");
        }
        Duration check = ConfigValues.duration(breakerCheckMs, ChronoUnit.MILLIS, false);
        if (check == null) {
            breakerReason = ConfigValues.join(breakerReason, "analytics.storage.breaker-check-ms 须为正的时长");
        }
        Duration initial = ConfigValues.duration(breakerInitialDelayMs, ChronoUnit.MILLIS, true);
        if (initial == null) {
            breakerReason = ConfigValues.join(breakerReason, "analytics.storage.breaker-initial-delay-ms 须为非负时长");
        }
        Integer retryAfter = ConfigValues.positiveInt(breakerRetryAfterSeconds);
        if (retryAfter == null) {
            breakerReason = ConfigValues.join(breakerReason, "analytics.storage.breaker-retry-after-seconds 须为正整数");
        }
        String cron = watermarkCron == null ? "" : watermarkCron.strip();
        if (!ConfigValues.isValidCronOrDisabled(cron)) {
            reason = ConfigValues.join(reason, "analytics.storage.watermark-cron 不是合法 cron");
            cron = DEFAULT_WATERMARK_CRON;
        }
        this.alertBytes = alert == null ? DEFAULT_ALERT : alert;
        this.stopBytes = stop == null ? DEFAULT_STOP : stop;
        this.monthEventsHint = hint == null ? DEFAULT_MONTH_EVENTS_HINT : hint;
        this.hintMonths = months == null ? DEFAULT_HINT_MONTHS : months;
        this.breakerCheckMs = check == null ? DEFAULT_BREAKER_CHECK_MS : Math.max(1, check.toMillis());
        this.breakerInitialDelay = initial == null ? Duration.ZERO : initial;
        this.breakerRetryAfterSeconds = retryAfter == null ? DEFAULT_RETRY_AFTER : retryAfter;
        this.watermarkCron = cron;
        this.breakerInvalidReason = breakerReason;
        this.invalidReason = ConfigValues.join(breakerReason, reason);
        if (breakerReason != null) {
            log.warn("analytics storage breaker disabled: invalid config ({})", breakerReason);
        }
        if (reason != null) {
            log.warn("analytics storage config invalid, using defaults: ({})", reason);
        }
    }

    /** 测试 / 代码内构造用（首延 0、水位 cron 默认）。非法值同样不抛。 */
    public AnalyticsStorageProperties(DataSize alertBytes, DataSize stopBytes, long monthEventsHint, int hintMonths,
            long breakerCheckMs, int breakerRetryAfterSeconds) {
        this(bytes(alertBytes), bytes(stopBytes), String.valueOf(monthEventsHint), String.valueOf(hintMonths),
                String.valueOf(breakerCheckMs), "0", String.valueOf(breakerRetryAfterSeconds), DEFAULT_WATERMARK_CRON);
    }

    private static String bytes(DataSize d) {
        return d == null ? "" : String.valueOf(d.toBytes());
    }

    /** 全默认值（测试用）。 */
    public static AnalyticsStorageProperties defaults() {
        return new AnalyticsStorageProperties(DEFAULT_ALERT, DEFAULT_STOP, DEFAULT_MONTH_EVENTS_HINT,
                DEFAULT_HINT_MONTHS, DEFAULT_BREAKER_CHECK_MS, DEFAULT_RETRY_AFTER);
    }

    public DataSize alertBytes() {
        return alertBytes;
    }

    public DataSize stopBytes() {
        return stopBytes;
    }

    public long monthEventsHint() {
        return monthEventsHint;
    }

    public int hintMonths() {
        return hintMonths;
    }

    public long breakerCheckMs() {
        return breakerCheckMs;
    }

    public Duration breakerInitialDelay() {
        return breakerInitialDelay;
    }

    public int breakerRetryAfterSeconds() {
        return breakerRetryAfterSeconds;
    }

    /** 水位每日任务 cron（合法 cron 或 {@code "-"}）。 */
    public String watermarkCron() {
        return watermarkCron;
    }

    /** 配置是否全部合法。 */
    public boolean valid() {
        return invalidReason == null;
    }

    /**
     * 「按水位停收」是否可用：只有熔断相关键（{@code stop-bytes} / {@code breaker-*}）非法时才停用（复审 R3：水位告警、
     * 月事件量提示、水位 cron 等无关键写错时回退默认即可，不该连带关掉磁盘保护）。
     */
    public boolean breakerEnabled() {
        return breakerInvalidReason == null;
    }

    /** 熔断相关键非法的原因（只含键名）；合法 = null。 */
    public String breakerInvalidReason() {
        return breakerInvalidReason;
    }

    /** 配置非法的原因（只含键名）；合法 = null。 */
    public String invalidReason() {
        return invalidReason;
    }
}

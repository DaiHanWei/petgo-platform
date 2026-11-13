package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.config.ConfigValues;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 汇总配置 {@code analytics.rollup.*}（analytics-pipeline Story 4.1），全部有默认值。
 *
 * <p>🔴 由 {@link RollupConfig} 上的 {@code @EnableConfigurationProperties} 绑定；漏了它会静默取默认值
 * （见 {@code DailyReportConfig} 注释的教训）。
 *
 * <p>🔴 <b>配置非法不阻断后端启动</b>（Story 4.9 AC8 / AC9，写法同 4-2 的 {@link Ga4SnapshotProperties}）：全部键按字符串绑定、
 * 自行解析；任一键非法（格式非法、非正数、{@code flase}、非法 cron）→ {@link #enabled()} 为 false（定时汇总与手动重跑都跳过），
 * {@link #invalidReason()} 只含键名、不含值，启动时打 WARN，{@code AnalyticsConfigAlert} 在应用就绪时发 TECH 告警。
 * 非法键回退为默认值（尤其 {@code statement-timeout}：它被拼进 {@code SET LOCAL}，非法值绝不能流出去）。
 * 原实现在紧凑构造器里抛 {@code IllegalArgumentException} / 类型绑定失败，一个 env 拼错就让整个后端起不来（4-2 复审 R1）。
 *
 * <ul>
 *   <li>{@code enabled}：总开关；false → 定时汇总与手动重跑都跳过（默认 true）</li>
 *   <li>{@code cron}：定时汇总 cron（WIB，默认 {@code 0 0 3 * * *}；{@code "-"} = 不注册定时任务）。由
 *       {@code AnalyticsSchedulingConfig} 编程式注册，不再用 {@code @Scheduled(cron = "${…}")} 直接绑定</li>
 *   <li>{@code statement-timeout}：单元事务内 {@code SET LOCAL statement_timeout} 的值（默认 {@code 600s}；写入账号默认 120s）</li>
 *   <li>{@code install-lookback-days}：「首次安装 / 首次注册」的回看天数（默认 92 = 明细保留期）</li>
 *   <li>{@code retention-days}：汇总能回到的最早日期 = 昨天(WIB) − 本值（默认 92）：续算落后更多时截断并告警，
 *       手动重跑的 {@code from} 不得早于它</li>
 *   <li>{@code rerun-fixed-delay-ms} / {@code rerun-initial-delay-ms}：{@link RollupRerunJob} 轮询间隔 / 首延（默认 60000 毫秒；
 *       纯数字按毫秒，也可写 {@code 60s}）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "analytics.rollup")
public final class AnalyticsRollupProperties {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRollupProperties.class);

    static final String DEFAULT_CRON = "0 0 3 * * *";
    static final String DEFAULT_STATEMENT_TIMEOUT = "600s";
    static final int DEFAULT_DAYS = 92;
    static final Duration DEFAULT_RERUN_DELAY = Duration.ofMinutes(1);

    /** {@code SET LOCAL} 不能绑定参数，只能拼接：值必须形如 {@code 600s} / {@code 10min} / {@code 600000}。 */
    private static final Pattern TIMEOUT_FORMAT = Pattern.compile("^\\d{1,9}(ms|s|min|h)?$");

    private final boolean enabled;
    private final String cron;
    private final String statementTimeout;
    private final int installLookbackDays;
    private final int retentionDays;
    private final Duration rerunFixedDelay;
    private final Duration rerunInitialDelay;
    /** 非 null = 配置非法（已停用汇总），只含键名。 */
    private final String invalidReason;

    /** Spring 绑定用：按字符串接收，非法值不抛、改为停用汇总。 */
    @ConstructorBinding
    public AnalyticsRollupProperties(@DefaultValue("true") String enabled,
            @DefaultValue(DEFAULT_CRON) String cron,
            @DefaultValue(DEFAULT_STATEMENT_TIMEOUT) String statementTimeout,
            @DefaultValue("92") String installLookbackDays,
            @DefaultValue("92") String retentionDays,
            @DefaultValue("60000") String rerunFixedDelayMs,
            @DefaultValue("60000") String rerunInitialDelayMs) {
        String reason = null;
        Boolean on = ConfigValues.bool(enabled);
        if (on == null) {
            reason = ConfigValues.join(reason, "analytics.rollup.enabled 不是 true / false");
        }
        String c = cron == null ? "" : cron.strip();
        if (!ConfigValues.isValidCronOrDisabled(c)) {
            reason = ConfigValues.join(reason, "analytics.rollup.cron 不是合法 cron");
            c = DEFAULT_CRON;
        }
        String t = statementTimeout == null ? "" : statementTimeout.strip();
        if (!TIMEOUT_FORMAT.matcher(t).matches()) {
            reason = ConfigValues.join(reason, "analytics.rollup.statement-timeout 格式非法（例：600s / 10min）");
            t = DEFAULT_STATEMENT_TIMEOUT;
        }
        Integer lookback = ConfigValues.positiveInt(installLookbackDays);
        if (lookback == null) {
            reason = ConfigValues.join(reason, "analytics.rollup.install-lookback-days 须为正整数");
        }
        Integer retention = ConfigValues.positiveInt(retentionDays);
        if (retention == null) {
            reason = ConfigValues.join(reason, "analytics.rollup.retention-days 须为正整数");
        }
        Duration fixed = ConfigValues.duration(rerunFixedDelayMs, ChronoUnit.MILLIS, false);
        if (fixed == null) {
            reason = ConfigValues.join(reason, "analytics.rollup.rerun-fixed-delay-ms 须为正的时长");
        }
        Duration initial = ConfigValues.duration(rerunInitialDelayMs, ChronoUnit.MILLIS, true);
        if (initial == null) {
            reason = ConfigValues.join(reason, "analytics.rollup.rerun-initial-delay-ms 须为非负时长");
        }
        this.enabled = reason == null && on;
        this.cron = c;
        this.statementTimeout = t;
        this.installLookbackDays = lookback == null ? DEFAULT_DAYS : lookback;
        this.retentionDays = retention == null ? DEFAULT_DAYS : retention;
        this.rerunFixedDelay = fixed == null ? DEFAULT_RERUN_DELAY : fixed;
        this.rerunInitialDelay = initial == null ? DEFAULT_RERUN_DELAY : initial;
        this.invalidReason = reason;
        if (reason != null) {
            log.warn("analytics rollup disabled: invalid config ({})", reason);
        }
    }

    /** 测试 / 代码内构造用（重跑轮询取默认值）。非法值同样不抛，改为停用。 */
    public AnalyticsRollupProperties(boolean enabled, String cron, String statementTimeout, int installLookbackDays,
            int retentionDays) {
        this(String.valueOf(enabled), cron, statementTimeout, String.valueOf(installLookbackDays),
                String.valueOf(retentionDays), "60000", "60000");
    }

    /** 全默认值（测试用）。 */
    public static AnalyticsRollupProperties defaults() {
        return new AnalyticsRollupProperties(true, DEFAULT_CRON, DEFAULT_STATEMENT_TIMEOUT, DEFAULT_DAYS, DEFAULT_DAYS);
    }

    /** 是否启用（配置非法时恒为 false）。 */
    public boolean enabled() {
        return enabled;
    }

    /** 定时汇总 cron（合法 cron 或 {@code "-"}；非法时为默认值，但此时 {@link #enabled()} 为 false、任务不注册）。 */
    public String cron() {
        return cron;
    }

    /** 已校验的 {@code statement_timeout} 值（非法时为默认 {@code 600s}）。 */
    public String statementTimeout() {
        return statementTimeout;
    }

    public int installLookbackDays() {
        return installLookbackDays;
    }

    public int retentionDays() {
        return retentionDays;
    }

    public Duration rerunFixedDelay() {
        return rerunFixedDelay;
    }

    public Duration rerunInitialDelay() {
        return rerunInitialDelay;
    }

    /** 配置非法的原因（只含键名）；合法 = null。 */
    public String invalidReason() {
        return invalidReason;
    }
}

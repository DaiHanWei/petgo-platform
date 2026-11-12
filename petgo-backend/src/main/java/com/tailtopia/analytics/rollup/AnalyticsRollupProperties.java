package com.tailtopia.analytics.rollup;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 汇总配置 {@code analytics.rollup.*}（analytics-pipeline Story 4.1），全部有默认值。
 *
 * <p>🔴 由 {@link RollupConfig} 上的 {@code @EnableConfigurationProperties} 绑定；漏了它会静默取默认值
 * （见 {@code DailyReportConfig} 注释的教训）。
 *
 * @param enabled              总开关；false → 定时汇总与手动重跑都跳过（默认 true）
 * @param cron                 定时汇总 cron（WIB，默认 {@code 0 0 3 * * *}；{@code @Scheduled} 直接读同名占位符，这里只做记录）
 * @param statementTimeout     单元事务内 {@code SET LOCAL statement_timeout} 的值（默认 {@code 600s}；写入账号默认 120s）
 * @param installLookbackDays  「首次安装 / 首次注册」的回看天数（默认 92 = 明细保留期）
 * @param retentionDays        汇总能回到的最早日期 = 昨天(WIB) − 本值（默认 92 = 明细保留期）：续算落后更多时截断并告警，
 *                             手动重跑的 {@code from} 不得早于它
 */
@ConfigurationProperties(prefix = "analytics.rollup")
public record AnalyticsRollupProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 0 3 * * *") String cron,
        @DefaultValue("600s") String statementTimeout,
        @DefaultValue("92") int installLookbackDays,
        @DefaultValue("92") int retentionDays) {

    /** {@code SET LOCAL} 不能绑定参数，只能拼接：值必须形如 {@code 600s} / {@code 10min} / {@code 600000}。 */
    private static final Pattern TIMEOUT_FORMAT = Pattern.compile("^\\d{1,9}(ms|s|min|h)?$");

    public AnalyticsRollupProperties {
        String t = statementTimeout == null ? "" : statementTimeout.strip();
        if (!TIMEOUT_FORMAT.matcher(t).matches()) {
            throw new IllegalArgumentException("analytics.rollup.statement-timeout 格式非法（例：600s / 10min）");
        }
        statementTimeout = t;
        if (installLookbackDays <= 0 || retentionDays <= 0) {
            throw new IllegalArgumentException("analytics.rollup.install-lookback-days / retention-days 必须为正");
        }
    }

    /** 全默认值（测试用）。 */
    public static AnalyticsRollupProperties defaults() {
        return new AnalyticsRollupProperties(true, "0 0 3 * * *", "600s", 92, 92);
    }
}

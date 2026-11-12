package com.tailtopia.analytics.rollup;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * GA4 快照任务配置 {@code analytics.ga4-snapshot.*}（analytics-pipeline Story 4.2），全部有默认值。
 * 由 {@link RollupConfig} 上的 {@code @EnableConfigurationProperties} 绑定。
 *
 * <p>🔴 <b>配置非法不阻断后端启动</b>（架构「故障隔离：只丢埋点，不伤业务」/「启动隔离」）：{@code enabled} 与
 * {@code backfill-days} 按字符串绑定、自行解析——值非法（如 env {@code ANALYTICS_GA4_SNAPSHOT_ENABLED=flase}）时只停用快照任务，
 * 启动时打 WARN，{@link Ga4DailySnapshotJob} 在应用就绪时发 TECH 告警。（代码复审 2026-11-12：原实现直接抛异常 / Spring 类型转换失败，
 * 一个埋点 env 拼错会让整个业务后端起不来。）
 *
 * <ul>
 *   <li>{@code enabled}：开关（默认 true；stag 设 {@code ANALYTICS_GA4_SNAPSHOT_ENABLED=false}：GA4 属性只有生产包数据）</li>
 *   <li>{@code cron}：cron（WIB，默认 {@code 0 30 2 * * *}，早于 03:00 的汇总；{@code @Scheduled} 直接读同名占位符，这里只做记录）</li>
 *   <li>{@code backfill-days}：补拉窗口：最近多少天内 {@code ga4_daily_snapshot} 缺行的日期也补拉（默认 14，须在 3–92 之间）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "analytics.ga4-snapshot")
public final class Ga4SnapshotProperties {

    private static final Logger log = LoggerFactory.getLogger(Ga4SnapshotProperties.class);

    /** 每次固定回拉的天数（D−1、D−2、D−3，架构 D-11）。 */
    public static final int REFETCH_DAYS = 3;
    static final int DEFAULT_BACKFILL_DAYS = 14;
    static final int MAX_BACKFILL_DAYS = 92;

    private final boolean enabled;
    private final String cron;
    private final int backfillDays;
    /** 非 null = 配置非法（已停用快照任务），内容只含键名，不含配置值。 */
    private final String invalidReason;

    /** Spring 绑定用：按字符串接收，非法值不抛、改为停用。 */
    @ConstructorBinding
    public Ga4SnapshotProperties(@DefaultValue("true") String enabled, @DefaultValue("0 30 2 * * *") String cron,
            @DefaultValue("14") String backfillDays) {
        String reason = null;
        String e = enabled == null ? "" : enabled.strip().toLowerCase(Locale.ROOT);
        boolean on;
        if ("true".equals(e)) {
            on = true;
        } else if ("false".equals(e)) {
            on = false;
        } else {
            on = false;
            reason = "analytics.ga4-snapshot.enabled 不是 true / false";
        }
        Integer days = parseDays(backfillDays);
        if (days == null) {
            on = false;
            reason = reason == null ? "analytics.ga4-snapshot.backfill-days 须为 3–92 的整数" : reason;
            days = DEFAULT_BACKFILL_DAYS;
        }
        this.enabled = on;
        this.cron = cron;
        this.backfillDays = days;
        this.invalidReason = reason;
        if (reason != null) {
            log.warn("analytics ga4 snapshot disabled: invalid config ({})", reason);
        }
    }

    /** 测试 / 代码内构造用。 */
    public Ga4SnapshotProperties(boolean enabled, String cron, int backfillDays) {
        this(String.valueOf(enabled), cron, String.valueOf(backfillDays));
    }

    private static Integer parseDays(String raw) {
        try {
            int d = Integer.parseInt(raw == null ? "" : raw.strip());
            return d < REFETCH_DAYS || d > MAX_BACKFILL_DAYS ? null : d;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 全默认值（测试用）。 */
    public static Ga4SnapshotProperties defaults() {
        return new Ga4SnapshotProperties(true, "0 30 2 * * *", DEFAULT_BACKFILL_DAYS);
    }

    /** 是否启用（配置非法时恒为 false）。 */
    public boolean enabled() {
        return enabled;
    }

    public String cron() {
        return cron;
    }

    public int backfillDays() {
        return backfillDays;
    }

    /** 配置非法的原因（只含键名）；合法 = null。 */
    public String invalidReason() {
        return invalidReason;
    }
}

package com.tailtopia.analytics;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 日活来源开关 {@code analytics.dau-source}（analytics-pipeline Story 4.2 AC6）。
 *
 * <p>Story 1.1 没有 {@code @ConfigurationProperties("analytics")} 类，按 story 用这个只含一个字段的小属性类绑定
 * （其余 {@code analytics.*} 子键各有自己的属性类，这里只认 {@code dau-source}，其他键忽略）。
 * 由 {@code RollupConfig} 上的 {@code @EnableConfigurationProperties} 注册（漏了会静默取默认值）。
 * 本 story 只提供开关，Story 4.7 读取。
 *
 * <p>🔴 <b>值非法不阻断后端启动</b>（架构「故障隔离」，代码复审 2026-11-12）：按字符串绑定、自行解析（不区分大小写），
 * 非法值（如 env {@code ANALYTICS_DAU_SOURCE=GA-4}）回退默认 {@link DauSource#GA4} 并打 WARN，
 * {@code Ga4DailySnapshotJob} 在应用就绪时发 TECH 告警。
 */
@ConfigurationProperties(prefix = "analytics")
public final class AnalyticsDauSourceProperties {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsDauSourceProperties.class);

    private final DauSource dauSource;
    private final boolean valid;

    /** @param dauSource 日活来源，默认 {@code GA4}（env {@code ANALYTICS_DAU_SOURCE}） */
    public AnalyticsDauSourceProperties(@DefaultValue("GA4") String dauSource) {
        DauSource parsed = null;
        try {
            parsed = DauSource.valueOf((dauSource == null ? "" : dauSource.strip()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("analytics.dau-source invalid, falling back to GA4");
        }
        this.valid = parsed != null;
        this.dauSource = parsed == null ? DauSource.GA4 : parsed;
    }

    /** 日活来源（配置非法时为 {@link DauSource#GA4}）。 */
    public DauSource dauSource() {
        return dauSource;
    }

    /** 配置值是否合法（false = 已回退 GA4）。 */
    public boolean valid() {
        return valid;
    }
}

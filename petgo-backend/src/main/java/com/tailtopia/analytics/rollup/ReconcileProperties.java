package com.tailtopia.analytics.rollup;

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 日活对账配置 {@code analytics.reconcile.*}（analytics-pipeline Story 4.2，D-11）。
 * 由 {@link RollupConfig} 上的 {@code @EnableConfigurationProperties} 绑定。
 *
 * <p>🔴 格式非法<b>不阻断后端启动</b>（架构「故障隔离」）：{@link #valid()} 为 false，启动时打 WARN，
 * {@link Ga4DailySnapshotJob} 停用并在应用就绪时发 TECH 告警（代码复审 2026-11-12）。
 *
 * @param minAppVersion 新版本阈值（默认 {@code 1.5.0}：自建埋点从这一版起上报）。GA4「新版本日活」= appVersion ≥ 本值，
 *                      按数字逐段比较；写进 {@code ga4_daily_snapshot.min_app_version}（varchar(16)）
 */
@ConfigurationProperties(prefix = "analytics.reconcile")
public record ReconcileProperties(@DefaultValue("1.5.0") String minAppVersion) {

    private static final Logger log = LoggerFactory.getLogger(ReconcileProperties.class);
    private static final Pattern FORMAT = Pattern.compile("^\\d{1,4}(\\.\\d{1,4}){0,2}$");

    public ReconcileProperties {
        minAppVersion = minAppVersion == null ? "" : minAppVersion.strip();
        if (!FORMAT.matcher(minAppVersion).matches()) {
            log.warn("analytics ga4 snapshot disabled: invalid config (analytics.reconcile.min-app-version 格式非法，例：1.5.0)");
        }
    }

    /** 阈值格式是否合法（最多三段、每段 1–4 位数字，最长 14 字符，适配 varchar(16)）。 */
    public boolean valid() {
        return FORMAT.matcher(minAppVersion).matches();
    }

    /** 全默认值（测试用）。 */
    public static ReconcileProperties defaults() {
        return new ReconcileProperties("1.5.0");
    }
}

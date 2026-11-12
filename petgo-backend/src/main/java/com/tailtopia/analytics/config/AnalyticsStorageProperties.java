package com.tailtopia.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 埋点库存储水位与停写熔断配置 {@code analytics.storage.*}（analytics-pipeline Story 1.10，FR-16 / 架构「磁盘熔断」），全部有默认值。
 *
 * <p>字节阈值用 {@link DataSize} 绑定：yaml 可写 {@code 40GB}（Spring 的 GB = 1024³ 字节，与 {@code pg_size_pretty} 同口径），
 * 也可写纯数字（字节；L1 测试配成 {@code 1} 即 1 字节）。
 *
 * @param alertBytes               超过即 TECH 告警（默认 40 GB）
 * @param stopBytes                超过即接收端停写 429（默认 50 GB），降回到此值及以下自动恢复
 * @param monthEventsHint          本 WIB 自然月事件量达到此值提示「评估切回第三方」（默认 6,000 万）
 * @param hintMonths               按月增速外推将在此月数内触及 {@code alertBytes} 时提示「评估切回第三方」（默认 3）
 * @param breakerCheckMs           熔断检查间隔（毫秒，默认 5 分钟）
 * @param breakerRetryAfterSeconds 熔断时 429 的 {@code Retry-After}（秒，默认 300，与检查周期一致）
 */
@ConfigurationProperties(prefix = "analytics.storage")
public record AnalyticsStorageProperties(
        @DefaultValue("40GB") DataSize alertBytes,
        @DefaultValue("50GB") DataSize stopBytes,
        @DefaultValue("60000000") long monthEventsHint,
        @DefaultValue("3") int hintMonths,
        @DefaultValue("300000") long breakerCheckMs,
        @DefaultValue("300") int breakerRetryAfterSeconds) {

    public AnalyticsStorageProperties {
        if (alertBytes == null || alertBytes.toBytes() <= 0) {
            throw new IllegalArgumentException("analytics.storage.alert-bytes 必须为正");
        }
        if (stopBytes == null || stopBytes.toBytes() <= 0) {
            throw new IllegalArgumentException("analytics.storage.stop-bytes 必须为正");
        }
        if (monthEventsHint <= 0 || hintMonths <= 0 || breakerCheckMs <= 0 || breakerRetryAfterSeconds <= 0) {
            throw new IllegalArgumentException("analytics.storage.* 数值配置必须为正");
        }
    }

    /** 全默认值（测试用）。 */
    public static AnalyticsStorageProperties defaults() {
        return new AnalyticsStorageProperties(DataSize.ofGigabytes(40), DataSize.ofGigabytes(50), 60_000_000L, 3,
                300_000L, 300);
    }
}

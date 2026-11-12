package com.tailtopia.analytics.ingest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 埋点接收配置 {@code analytics.ingest.*}（analytics-pipeline Story 1.4），全部有默认值。
 *
 * @param maxRequestBytes            单个请求体上限（A6：500 KB），超限 413
 * @param maxEventBytes              单条事件序列化后上限（A6：32 KB），任一条超限整批 413
 * @param maxEventsPerBatch          单批条数上限，超限 413
 * @param queueMaxBytes              内存队列按估算字节的上限（D2 破例，默认 64 MB），满了 429
 * @param queueFullRetryAfterSeconds 队列满时 429 的 {@code Retry-After}（秒）
 * @param flushBatchSize             写库线程每批最多条数
 * @param flushIntervalMs            写库线程攒批最长等待（毫秒）
 * @param shutdownDrainTimeout       优雅停机时把队列写完的最长等待
 * @param rateLimit                  限流阈值
 */
@ConfigurationProperties(prefix = "analytics.ingest")
public record IngestProperties(
        @DefaultValue("512000") int maxRequestBytes,
        @DefaultValue("32768") int maxEventBytes,
        @DefaultValue("500") int maxEventsPerBatch,
        @DefaultValue("67108864") long queueMaxBytes,
        @DefaultValue("30") int queueFullRetryAfterSeconds,
        @DefaultValue("500") int flushBatchSize,
        @DefaultValue("1000") long flushIntervalMs,
        @DefaultValue("20s") Duration shutdownDrainTimeout,
        @DefaultValue RateLimit rateLimit) {

    /**
     * 限流阈值：以匿名 id 为主（{@code perDevice}），IP 只做很宽的兜底（{@code perIp}，印尼移动网络 CGNAT 共用出口）。
     *
     * @param perDevice     每个 {@code anonymousId} 每窗口最多请求数
     * @param perIp         每个 IP（哈希后）每窗口最多请求数
     * @param windowSeconds 固定窗口长度（秒）
     */
    public record RateLimit(
            @DefaultValue("120") int perDevice,
            @DefaultValue("3000") int perIp,
            @DefaultValue("60") int windowSeconds) {
    }

    /** 全默认值（测试用）。 */
    static IngestProperties defaults() {
        return new IngestProperties(512_000, 32_768, 500, 67_108_864L, 30, 500, 1000, Duration.ofSeconds(20),
                new RateLimit(120, 3000, 60));
    }
}

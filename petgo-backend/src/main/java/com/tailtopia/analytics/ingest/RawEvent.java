package com.tailtopia.analytics.ingest;

import java.time.Instant;

/**
 * 一条待写入原始层 {@code events_raw} 的事件（analytics-pipeline Story 1.4）。字段与表列一一对应，外加
 * {@link #estimatedBytes} 供 {@link IngestQueue} 按字节计上限。
 *
 * <p>🔴 本类型<b>不带任何 HTTP 相关的东西</b>：Story 1.9 的服务端事件（{@code SelfHostedAnalyticsClient}）也构造它进同一队列。
 *
 * <ul>
 *   <li>{@code receivedAt}：服务端收到时间（UTC），分区键。接口来的事件在 controller 入口取一次（同批同值）。</li>
 *   <li>{@code userKey}：用户标识哈希（= {@code AnalyticsDistinctId}）。接口来的事件在身份判定前，这里放的是请求里声明的
 *       {@code userId} 原值，由 {@code IdentityResolver} 决定最终的 {@code userKey} / {@code userId} / {@code identityUnverified}。</li>
 *   <li>{@code userId}：内部用户 id，只在埋点库内部用。</li>
 *   <li>{@code propertiesJson} / {@code contextJson}：JSON 对象文本，缺省 {@code "{}"}，写库时 {@code ::jsonb}。</li>
 * </ul>
 *
 * <p>日志里只允许出现 {@code messageId} 与事件名计数，其余字段一律不打（架构 §实现规范 4）。
 */
public record RawEvent(
        String messageId,
        String event,
        Instant clientTs,
        Instant sentAt,
        Instant receivedAt,
        String anonymousId,
        String userKey,
        Long userId,
        Source source,
        String propertiesJson,
        String contextJson,
        boolean identityUnverified,
        boolean unknownEvent,
        long estimatedBytes) {

    /** {@code events_raw.source}（CHECK 约束值域）。 */
    public enum Source { APP, SERVER, H5 }

    /** 每条事件的固定开销估算（对象头、引用、Instant 等），保守取值。 */
    static final long FIXED_OVERHEAD_BYTES = 256;

    static final String EMPTY_JSON = "{}";

    /**
     * 构造并按规则估算字节：各字符串字段长度 ×2 + JSON 长度 ×2 + 固定开销。
     * {@code propertiesJson} / {@code contextJson} 为空时按 {@code "{}"}。
     */
    public static RawEvent of(String messageId, String event, Instant clientTs, Instant sentAt, Instant receivedAt,
            String anonymousId, String userKey, Long userId, Source source, String propertiesJson,
            String contextJson, boolean identityUnverified, boolean unknownEvent) {
        String props = propertiesJson == null ? EMPTY_JSON : propertiesJson;
        String ctx = contextJson == null ? EMPTY_JSON : contextJson;
        long bytes = FIXED_OVERHEAD_BYTES
                + 2L * (len(messageId) + len(event) + len(anonymousId) + len(userKey))
                + 2L * (props.length() + ctx.length());
        return new RawEvent(messageId, event, clientTs, sentAt, receivedAt, anonymousId, userKey, userId, source,
                props, ctx, identityUnverified, unknownEvent, bytes);
    }

    /** 替换身份三项（{@code IdentityResolver} 用），字节估算按新的 {@code userKey} 重算。 */
    public RawEvent withIdentity(String newUserKey, Long newUserId, boolean newIdentityUnverified) {
        return of(messageId, event, clientTs, sentAt, receivedAt, anonymousId, newUserKey, newUserId, source,
                propertiesJson, contextJson, newIdentityUnverified, unknownEvent);
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }
}

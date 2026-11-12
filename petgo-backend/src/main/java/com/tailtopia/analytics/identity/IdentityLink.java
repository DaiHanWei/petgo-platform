package com.tailtopia.analytics.identity;

import java.time.Duration;
import java.time.Instant;

/**
 * {@code identity_links} 的一行（analytics-pipeline Story 1.5，D-10）：匿名 id ↔ 用户的一次登录关联。只追加。
 *
 * <p>由 {@link LinkTableIdentityResolver} 在请求线程里生成、挂在对应的 {@code user_identified} 事件上进队列，
 * 由 {@code IngestFlusher} 与事件在同一个埋点事务里写库。服务端事件永远不带关联。
 *
 * <p>🔴 {@link #toString()} 被覆写为不含任何字段值：匿名 id、用户标识、用户 id 一律不进日志（NFR-3）。
 *
 * @param anonymousId 匿名 id
 * @param userId      内部用户 id（只在埋点库内部用）
 * @param userKey     {@code AnalyticsDistinctId.of(userId)}
 * @param linkedAt    关联时间（UTC），见 {@link #correctedTime}
 * @param messageId   来源 {@code user_identified} 的 {@code messageId}（重试幂等）
 */
public record IdentityLink(String anonymousId, long userId, String userKey, Instant linkedAt, String messageId) {

    /** 与 {@code IngestValidator} 的时间下限一致；只防极端时钟偏差把校正值推出 PG {@code timestamptz} 范围、毒死整批。 */
    static final Instant MIN_TS = Instant.parse("0001-01-01T00:00:00Z");

    /**
     * 与明细层一致的校正时间（FR-9 / D-3）：{@code clientTs + (receivedAt − sentAt)}；晚于 {@code receivedAt} 取 {@code receivedAt}。
     * 任一入参为空时退回 {@code receivedAt}（再空则 {@code clientTs}）。
     */
    public static Instant correctedTime(Instant clientTs, Instant sentAt, Instant receivedAt) {
        if (clientTs == null || sentAt == null || receivedAt == null) {
            return receivedAt != null ? receivedAt : clientTs;
        }
        Instant corrected = clientTs.plus(Duration.between(sentAt, receivedAt));
        if (corrected.isAfter(receivedAt)) {
            return receivedAt;
        }
        return corrected.isBefore(MIN_TS) ? MIN_TS : corrected;
    }

    @Override
    public String toString() {
        return "IdentityLink[redacted]";
    }
}

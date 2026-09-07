package com.tailtopia.moderation.throttle.domain;

import java.time.Duration;
import java.time.Instant;

/** 限流期限（V1.1.6 Story 17.1 · AC4）。三档，落库 varchar + UPPER_SNAKE。 */
public enum ThrottleDuration {

    DAYS_7(Duration.ofDays(7)),
    DAYS_30(Duration.ofDays(30)),
    /** 永久：无到期时刻。只能靠手动解除。 */
    PERMANENT(null);

    private final Duration length;

    ThrottleDuration(Duration length) {
        this.length = length;
    }

    /**
     * 从 {@code from} 起算的到期时刻；永久返回 {@code null}。
     *
     * <p>🛡 「永久 ⇔ expires_at 为空」这条一致性同时由建表的 CHECK 守着 ——
     * 写反了会让系数在某个时点悄悄变化，而没有任何报错。
     */
    public Instant expiresFrom(Instant from) {
        return length == null ? null : from.plus(length);
    }
}

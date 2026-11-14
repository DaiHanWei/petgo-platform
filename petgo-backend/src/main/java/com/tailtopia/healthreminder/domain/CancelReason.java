package com.tailtopia.healthreminder.domain;

import java.util.Locale;

/**
 * 取消原因（落库与埋点同为小写原文，与迁移 {@code ck_health_reminders_reason} 一致）。{@link #OLD_APP} 是架构增量
 * 比 PRD §4 多出的一个：用户当天不满足 1.5.0 门槛时到期行作废，<b>不发埋点</b>。
 */
public enum CancelReason {
    SATISFIED,
    SKIPPED_CAP,
    SKIPPED_SCHEDULED_PUSH,
    EXPIRED_DEFER,
    MISSED_RUN,
    RECORD_DELETED,
    REBUILT,
    NEUTERED,
    PET_DELETED,
    READ_ONLY,
    PAUSED,
    CATCH_UP_SENT,
    QUEUE_RECHECK,
    OLD_APP;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CancelReason fromWire(String wire) {
        return wire == null ? null : valueOf(wire.toUpperCase(Locale.ROOT));
    }
}

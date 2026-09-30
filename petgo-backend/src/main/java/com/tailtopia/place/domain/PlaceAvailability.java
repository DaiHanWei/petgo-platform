package com.tailtopia.place.domain;

import java.time.Instant;

/**
 * 场所对用户的可用性（V1.3.2 Story 1.2 · AC3.2；Story 1.5 / 1.6 复用同一判定）。
 *
 * <p>🔴 <b>只有两值</b>：App 只需要知道「能不能点进去」。把 DELISTED / 软删 / MERGED 细分下发
 * 等于泄漏「下架 vs 不存在」的区分（V1.3.0 Story 1.5 决定刻意不可区分）。
 */
public enum PlaceAvailability {
    ACTIVE,
    UNAVAILABLE;

    /** {@code status=ACTIVE 且未软删} → ACTIVE，其余一律 UNAVAILABLE。 */
    public static PlaceAvailability of(String status, Instant deletedAt) {
        return PlaceStatus.ACTIVE.name().equals(status) && deletedAt == null ? ACTIVE : UNAVAILABLE;
    }
}

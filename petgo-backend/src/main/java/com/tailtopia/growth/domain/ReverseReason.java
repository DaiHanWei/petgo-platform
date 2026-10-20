package com.tailtopia.growth.domain;

/** 扣回 / 调整原因码（{@code growth_ledger.reason}，架构 AD-2）。 */
public enum ReverseReason {
    REFUND,
    UNLIKE,
    UNCOLLECT,
    TAKEDOWN,
    ADMIN_ADJUST
}

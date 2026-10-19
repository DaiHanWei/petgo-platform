package com.tailtopia.gift.domain;

/**
 * 付款后无法送达的原因（V1.4.0 gift Story 1.1 · AD-7）。交付前按此顺序判定：主人已注销 → 宠物已删除 → 送礼人拉黑了主人。
 */
public enum GiftUndeliveredReason {
    PET_DELETED,
    OWNER_DELETED,
    /** 送礼人在付款期间拉黑了主人（只看 {@code isBlocked(sender, owner)} 单向）。 */
    SENDER_BLOCKED
}

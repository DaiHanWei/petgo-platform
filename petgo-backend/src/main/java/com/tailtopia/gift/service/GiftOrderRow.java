package com.tailtopia.gift.service;

import java.time.Instant;

/**
 * 已送达订单的只读投影（V1.5.0 gift-pack Story 1-4）：逐笔列表 / 详情 / 拆礼物共用。只在服务内部流转，不直接下发。
 */
public record GiftOrderRow(long id, String publicToken, Long petId, long senderId, long giftId, String giftNameEn,
        String giftNameId, String giftIconUrl, long unitPrice, int quantity, Instant deliveredAt, Instant openedAt,
        String message, Instant messageHiddenAt) {

    public boolean opened() {
        return openedAt != null;
    }

    /** 有祝福语且未被隐藏。 */
    public boolean hasVisibleMessage() {
        return message != null && messageHiddenAt == null;
    }

    /** 面值 = unit_price × quantity（EXP 口径，D-7 / D-16）。 */
    public long faceValue() {
        return Math.multiplyExact(unitPrice, (long) quantity);
    }
}

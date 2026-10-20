package com.tailtopia.gift.event;

/**
 * 未送达订单已退回 PawCoin（V1.4.0 gift Story 1.2 · AD-9，本模块内部事件，过去式）。
 *
 * <p>由 {@code GiftRefundService.attempt} 在入账与置 {@code UNDELIVERED_REFUNDED} 的同一事务里发布；
 * 退回通知由 {@code GiftNotifyListener.onRefunded} 在<b>提交后</b>发（通知失败不影响退回结果）。只带订单 id，监听方自己读订单。
 *
 * @param giftOrderId 送礼订单 id
 */
public record GiftRefundedEvent(long giftOrderId) {
}

package com.tailtopia.gift.event;

/**
 * 送礼订单付款后无法送达（V1.4.0 gift Story 1.1 · AD-7，本模块内部事件，过去式）。
 *
 * <p>由 {@code GiftDeliveryService} 在订单置 {@code UNDELIVERED_REFUND_PENDING} 的同事务里同步发布；
 * 退回 PawCoin 与退回通知由 Story 1.2 在<b>提交后</b>监听（{@code REQUIRES_NEW} 退回 —— 不能在支付回调事务里
 * {@code credit}，抛异常会把到账一起回滚）。只带订单 id，监听方自己读订单。
 *
 * @param giftOrderId 送礼订单 id
 */
public record GiftUndeliveredEvent(long giftOrderId) {
}

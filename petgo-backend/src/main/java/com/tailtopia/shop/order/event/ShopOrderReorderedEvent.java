package com.tailtopia.shop.order.event;

/**
 * 迟到款已重新下单（v1.4.2 shop Story 4.2 AC4.9）：{@code LatePaymentService#reorderFromLatePayment} 在重下单事务内发布，
 * 消费方 {@code ShopOrderReorderedNotifyListener} 只在提交后（AFTER_COMMIT）给用户发 {@code SHOP_ORDER_REORDERED}。
 *
 * @param newOrderToken     新单 public_token（推送 targetRef / 深链）
 * @param newOrderDisplayNo 新单展示号（推送文案 {0}）
 */
public record ShopOrderReorderedEvent(long originalOrderId, long newOrderId, long userId, String newOrderToken,
        String newOrderDisplayNo) {
}

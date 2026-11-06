package com.tailtopia.shop.order.event;

/**
 * 非后台手工发货路径推动的「订单已发货」（v1.4.2 shop Story 5.1 AC8.4）：Biteship 回传揽收、运营「标记已发货」。
 * 监听方 AFTER_COMMIT + REQUIRES_NEW 发 {@code SHOP_ORDER_SHIPPED} 通知。后台手工发货仍在
 * {@code AdminShopOrderService} 内同步发，不走本事件。
 *
 * <p>🔒 只带 id / token，不带收件人信息。
 */
public record ShopOrderShippedEvent(long orderId, long userId, String orderToken) {
}

package com.tailtopia.gift.domain;

/**
 * 送礼订单状态（V1.4.0 gift Story 1.1 · AD-2，落库 {@code gift_orders.status} varchar(32) + CHECK）。
 *
 * <p>🔴 状态只经 {@code GiftDeliveryService}（交付）、退回服务（Story 1.2）与两个支付监听改变；
 * 交付用条件更新 {@code WHERE status = 'PENDING'} 保证回调与轮询只交付一次。
 */
public enum GiftOrderStatus {
    /** 待付（QRIS 已出码未到账；PawCoin 只在同事务内短暂存在）。 */
    PENDING,
    /** 已送达：礼物墙与粉丝榜已更新、已发 {@code GiftSentEvent}。 */
    DELIVERED,
    /** QRIS 付款窗超时。 */
    EXPIRED,
    /** QRIS 付款失败（网关拒付 / 用户取消 / 网关下单失败）。 */
    FAILED,
    /** QRIS 已到账但无法送达，待退回 PawCoin（Story 1.2 退回）。 */
    UNDELIVERED_REFUND_PENDING,
    /** 已退回 PawCoin（Story 1.2）。 */
    UNDELIVERED_REFUNDED,
    /** 退回重试耗尽，交人工（Story 1.2）。 */
    REFUND_MANUAL
}

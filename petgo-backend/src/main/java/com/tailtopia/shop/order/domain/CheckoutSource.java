package com.tailtopia.shop.order.domain;

/**
 * 订单的结算来源（v1.4.2 shop Story 3.2，D-8；落 {@code shop_orders.checkout_source}）。
 *
 * <p>🔴 列为 {@code NULL} = 本版之前下的单（PawCoin 仍在付款时扣，过渡规则见 {@code ShopOrderPaymentService}）；
 * 非空 = PawCoin 已在提交时扣（S-3）。
 */
public enum CheckoutSource {
    /** 新版 App 购物车结算 */
    CART,
    /** 新版 App 立即购买 */
    BUY_NOW,
    /** 旧版 App（≤1.4.1）下单，后端旧结算模式装配（S-4） */
    LEGACY,
    /** 4.2 迟到款重新下单所建新单 */
    REORDER
}

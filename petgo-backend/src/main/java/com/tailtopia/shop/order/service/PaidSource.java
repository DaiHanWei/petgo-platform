package com.tailtopia.shop.order.service;

/**
 * 订单「已付款」的来源（v1.4.2 shop Story 3.2 AC4，D-8 / D-11 I4 / C-4）。随 {@code ShopOrderPaidEvent.source} 下发。
 */
public enum PaidSource {
    /** 网关到账（QRIS / MIXED 回调） */
    GATEWAY,
    /** 现金段为 0、PawCoin 足额（提交时已扣币） */
    PURE_COIN,
    /** 应付为 0（满减 + 免运券抵光，C-4）：提交即结清，不出码 */
    ZERO_PAY,
    /** 4.2 迟到款重新下单 */
    REORDER
}

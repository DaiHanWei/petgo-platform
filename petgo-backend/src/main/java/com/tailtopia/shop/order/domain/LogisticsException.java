package com.tailtopia.shop.order.domain;

/**
 * 订单物流异常标记（v1.4.2 shop Story 5.1 建列、映射写入 {@link #PICKUP_FAILED} / {@link #RETURNED}；其余归 Story 5.2）。
 *
 * <p>决策 C-6：原 {@code LOST_DAMAGED} 拆为 {@link #LOST}、{@link #DAMAGED}（取消原因 {@code CancelReason.LOST_DAMAGED} 不拆）。
 */
public enum LogisticsException {
    PICKUP_FAILED,
    RETURNED,
    STALLED,
    LOST,
    DAMAGED;

    /** 退回 / 丢件 / 破损：收到 {@code delivered} 也不自动推进订单（交运营判断）。 */
    public boolean blocksAutoDelivery() {
        return this == RETURNED || this == LOST || this == DAMAGED;
    }
}

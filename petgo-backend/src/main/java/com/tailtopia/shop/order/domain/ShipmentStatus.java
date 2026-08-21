package com.tailtopia.shop.order.domain;

/**
 * 包裹状态（Story 4.1，S-2）。
 *
 * <p>只有两态：<b>发出</b>与<b>送达</b>。不建「运输中 / 派送中 / 异常」等中间态 ——
 * 那些只有接了承运商 API 才有真实来源，而 FR-103 明确不接。造一个没人能推进的状态
 * 等于给运营一个永远不准的字段。
 */
public enum ShipmentStatus {
    SHIPPED,
    DELIVERED
}

package com.tailtopia.shop.order.domain;

/**
 * 订单转 {@code SHIPPED} 的来源（v1.4.2 shop Story 5.1）。只在后台可查，App 侧一律不区分（PRD 旅程 D 第 1 条）。
 * 存量（1.4.2 前）已发货订单为 NULL。
 */
public enum ShippedSource {
    /** 后台手工运单发货，或运营「标记已发货」（有 Biteship 运单但先行确认打包）。 */
    MANUAL,
    /** Biteship 回传揽收（picked 及之后的状态兜底）。 */
    BITESHIP_PICKED
}

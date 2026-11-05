package com.tailtopia.shop.order.domain;

/**
 * 包裹 / 运单状态（Story 4.1，S-2；v1.4.2 shop Story 5.1 扩展）。
 *
 * <p>手工运单只有 {@link #SHIPPED} / {@link #DELIVERED} 两态（录入即已发出）。v1.4.2 接入 Biteship 后有了真实的
 * 中间态来源：运单在 Biteship 建单后、快递揽收前为 {@link #CREATED}；未揽收时被取消为 {@link #CANCELLED}。
 * 更细的物流进度（运输中 / 派送中）不建状态，存在 {@code provider_status} / {@code user_status_key}。
 */
public enum ShipmentStatus {
    /** Biteship 已建单、尚未揽收（v1.4.2 shop 5.1）。 */
    CREATED,
    SHIPPED,
    DELIVERED,
    /** Biteship 运单已取消（仅 {@link #CREATED} 可取消，v1.4.2 shop 5.1）。 */
    CANCELLED
}

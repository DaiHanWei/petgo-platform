package com.tailtopia.gift.domain;

/** 礼物目录行上下架状态（V1.4.0 gift Story 1.1 · AD-1）。只下线不物理删除：订单与礼物墙 FK 指向目录行。 */
public enum GiftCatalogStatus {
    ONLINE,
    OFFLINE
}

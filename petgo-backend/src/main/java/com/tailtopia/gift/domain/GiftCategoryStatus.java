package com.tailtopia.gift.domain;

/** 礼物分类上下线状态（V1.5.0 gift-pack Story 1-1 · D-1）。只下线不删除；与 {@link GiftCatalogStatus} 同形但不复用。 */
public enum GiftCategoryStatus {
    ONLINE,
    OFFLINE
}

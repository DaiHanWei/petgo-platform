package com.tailtopia.gift.domain;

/**
 * 礼物种类（V1.4.0 gift Story 1.1 · AD-1，落库 {@code gift_catalog.kind} varchar(16) + CHECK）。
 *
 * <p>{@code EXCLUSIVE} 必须带 {@code min_level ≥ 1}，{@code NORMAL} 的 {@code min_level} 必须为 NULL
 * （表级 CHECK {@code ck_gift_catalog_exclusive_level}）。
 */
public enum GiftKind {
    /** 普通礼物：按价位分组展示，人人可送。 */
    NORMAL,
    /** 专属礼物：送礼人等级 ≥ {@code min_level} 才能送（等级只经 {@code GrowthQueryService}）。 */
    EXCLUSIVE
}

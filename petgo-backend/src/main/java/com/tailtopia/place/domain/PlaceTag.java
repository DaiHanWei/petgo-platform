package com.tailtopia.place.domain;

/**
 * 宠物友好标签（V1.3.0 batch-b1 Story 1.1 · FR-112.1）。**多选 ≥1，6 个全集**。
 *
 * <p>🔴 与 {@link PlaceType} 同理：UI 稿 A5 的标签是示意省略，实现必须给全集（UX-DR4）。
 *
 * <p>落库为 {@code places.tags} 的 JSONB 数组元素（UPPER_SNAKE）。用 JSONB 而不是关联表是
 * 因为标签是<b>固定 6 值的枚举集合</b>、不可运营扩展、本版不参与任何 JOIN。
 */
public enum PlaceTag {
    /** 允许入内 */
    PETS_ALLOWED_INSIDE,
    /** 户外座位 */
    OUTDOOR_SEATING,
    /** 宠物餐食 */
    PET_MENU,
    /** 宠物活动区 */
    PET_PLAY_AREA,
    /** 需牵引 */
    LEASH_REQUIRED,
    /** 大型犬友好 */
    LARGE_DOG_FRIENDLY
}

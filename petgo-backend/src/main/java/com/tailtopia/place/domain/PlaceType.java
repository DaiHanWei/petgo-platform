package com.tailtopia.place.domain;

/**
 * 场所类型（V1.3.0 batch-b1 Story 1.1 · FR-112.1）。**单选，7 类全集**。
 *
 * <p>🔴 这 7 个值就是全集 —— UI 稿 A5 里的类型是<b>示意省略</b>，不是真实清单（UX-DR4）。
 * 加值必须同步 {@code ck_places_type}（DROP + ADD 重列全集）与 App 端的映射表。
 *
 * <p>落库 {@code varchar} + UPPER_SNAKE（命名映射链）。
 */
public enum PlaceType {
    /** 咖啡店 */
    CAFE,
    /** 餐厅 */
    RESTAURANT,
    /** 公园 */
    PARK,
    /** 商场 */
    MALL,
    /** 酒店民宿 */
    HOTEL,
    /** 宠物服务 */
    PET_SERVICE,
    /** 其他 */
    OTHER
}

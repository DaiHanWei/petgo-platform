package com.tailtopia.admin.places.domain;

/** 场所状态（V1.3.0 Story 5.1，AD-5；落库 varchar UPPER_SNAKE，CHECK {@code ck_places_status}）。 */
public enum PlaceStatus {
    /** 上架。 */
    ACTIVE,
    /** 下架：对用户端「不存在」，可恢复。 */
    DELISTED,
    /** 已并入 {@code merged_into_id} 指向的保留场所（契约 X-1，App 直链据此跳转）。 */
    MERGED
}

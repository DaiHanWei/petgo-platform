package com.tailtopia.admin.places.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 场所类型（Story 5.1 → 2026-09-18 场所表对齐 D1）。
 * <p>值域由 App 端 FR-112 定：当初不知道全集，所以只做「已知 / 未知」软校验、表上不加 CHECK；
 * 对齐后全集已知 —— 与 App 侧 {@code com.tailtopia.place.domain.PlaceType} 同 7 值，表上已加 {@code ck_places_type}，
 * 后台录入 / 编辑也改为服务端拒绝未知值（写进去会撞 CHECK 出 500）。
 * <p>🔴 加类型 = 两个枚举 + CHECK 约束 + App 与后台的多语言文案一起改。
 */
public enum PlaceType {
    CAFE,
    RESTAURANT,
    PARK,
    MALL,
    HOTEL,
    PET_SERVICE,
    OTHER;

    public static final List<String> KNOWN = Arrays.stream(values()).map(Enum::name).toList();

    public static boolean isKnown(String placeType) {
        return placeType != null && KNOWN.contains(placeType);
    }
}

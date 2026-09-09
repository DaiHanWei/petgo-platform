package com.tailtopia.admin.places.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 场所类型的<b>软校验</b>枚举（Story 5.1 Dev Notes）：值域由 App 端 FR-112 定义，表 {@code places.place_type} 不加 CHECK 以免两分支撞值；
 * 后台只用本枚举做「已知 / 未知」判断（未知值只警告不拒），待 App 分支合入后两边对齐再补 CHECK（Completion Notes 待办）。
 * 列本身以 {@code String} 映射（{@link Place#getPlaceType()}），UI 稿 2-20 出现的四种先列入。
 */
public enum PlaceType {
    CAFE,
    PET_PARK,
    PET_HOTEL,
    PET_FRIENDLY_RESTAURANT;

    public static final List<String> KNOWN = Arrays.stream(values()).map(Enum::name).toList();

    public static boolean isKnown(String placeType) {
        return placeType != null && KNOWN.contains(placeType);
    }
}

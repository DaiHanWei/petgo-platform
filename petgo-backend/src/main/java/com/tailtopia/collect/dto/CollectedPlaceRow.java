package com.tailtopia.collect.dto;

import com.tailtopia.place.domain.PlaceType;
import java.time.Instant;

/**
 * 我的场所收藏一行（V1.4.0 ④ Story 2.2 · AD-11 · UI E6 / E7）。Jackson NON_NULL。
 *
 * <p>{@code placeToken} / {@code name} 取<b>解析后的场所</b>（MERGED → 保留场所，P-6）；{@code name} 不可见时也下发
 * （UI「保留名称置灰」）。{@code available = false} 时 {@code place} 为 null（不下发类型、地址、封面）。
 * 不下发场所 id、merged_into_id、收藏行 id、坐标。
 */
public record CollectedPlaceRow(String placeToken, Instant collectedAt, boolean available, String name, Place place) {

    /** @param coverUrl 与场所列表同口径的去 EXIF 缩略图（320）；无照片为 null */
    public record Place(PlaceType type, String coverUrl, String addressText) {
    }
}

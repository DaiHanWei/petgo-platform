package com.tailtopia.admin.places.service;

import com.tailtopia.shared.error.AppException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 场所坐标校验（V1.3.0 Story 5.3 AC1，D-33）。
 * <ul>
 * <li>硬拦（422）：纬度 ∉ [-90, 90]（{@code admin.err.places.latRange}）、经度 ∉ [-180, 180]（{@code lngRange}）、小数位 > 6（{@code coordPrecision}，
 * 去尾零后 {@code scale() <= 6}）。</li>
 * <li>软警告（200 + 黄条）：落在雅加达都会区（Jabodetabek）包围盒外——纬度 −6.9～−5.9、经度 106.3～107.3；运营从 Google Maps 抄错一位很常见，
 * 但业务确有雅加达以外的点（UI 稿 2-20 的 Bandung），所以只提示不拦。</li>
 * </ul>
 */
@Component
public class PlaceCoordinateValidator {

    static final BigDecimal JKT_LAT_MIN = new BigDecimal("-6.9");
    static final BigDecimal JKT_LAT_MAX = new BigDecimal("-5.9");
    static final BigDecimal JKT_LNG_MIN = new BigDecimal("106.3");
    static final BigDecimal JKT_LNG_MAX = new BigDecimal("107.3");
    static final int MAX_SCALE = 6;

    public void validate(BigDecimal lat, BigDecimal lng) {
        if (lat == null || lat.compareTo(new BigDecimal("-90")) < 0 || lat.compareTo(new BigDecimal("90")) > 0) {
            throw AppException.validation("纬度须在 -90 ~ 90").code("admin.err.places.latRange");
        }
        if (lng == null || lng.compareTo(new BigDecimal("-180")) < 0 || lng.compareTo(new BigDecimal("180")) > 0) {
            throw AppException.validation("经度须在 -180 ~ 180").code("admin.err.places.lngRange");
        }
        if (lat.stripTrailingZeros().scale() > MAX_SCALE || lng.stripTrailingZeros().scale() > MAX_SCALE) {
            throw AppException.validation("坐标最多保留 6 位小数").code("admin.err.places.coordPrecision");
        }
    }

    /** 雅加达都会区包围盒外 → true（只警告不拦）。调用前须已 {@link #validate}。 */
    public boolean isOutsideJakarta(BigDecimal lat, BigDecimal lng) {
        return lat.compareTo(JKT_LAT_MIN) < 0 || lat.compareTo(JKT_LAT_MAX) > 0
                || lng.compareTo(JKT_LNG_MIN) < 0 || lng.compareTo(JKT_LNG_MAX) > 0;
    }
}

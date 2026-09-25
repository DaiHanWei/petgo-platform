package com.tailtopia.place.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * L0（纯计算，无 DB）：距离排序的粗筛与直线距离口径（V1.3.0 batch-b1 Story 1.2 · AD-2）。
 */
class GeoBoxTest {

    /** 雅加达 Senopati 一带。 */
    private static final double JKT_LAT = -6.2350;
    private static final double JKT_LNG = 106.8100;

    @Test
    void distanceBetweenSamePointIsZero() {
        assertThat(GeoBox.distanceMeters(JKT_LAT, JKT_LNG, JKT_LAT, JKT_LNG)).isZero();
    }

    /**
     * 已知向量：Monas（-6.1754, 106.8272）↔ Senopati（-6.2350, 106.8100）约 6.8 km。
     * 容差 200 m —— 直线距离本身就是近似，这条用例守的是「量级与算法对不对」，不是测地线精度。
     */
    @Test
    void distanceMatchesKnownJakartaVector() {
        double m = GeoBox.distanceMeters(-6.1754, 106.8272, JKT_LAT, JKT_LNG);
        assertThat(m).isCloseTo(6_840d, org.assertj.core.data.Offset.offset(200d));
    }

    /** 经度差在赤道附近 1° ≈ 111 km；纬度差 1° 恒 ≈ 111 km。 */
    @Test
    void oneDegreeIsRoughlyOneHundredElevenKilometers() {
        assertThat(GeoBox.distanceMeters(0, 0, 1, 0))
                .isCloseTo(111_195d, org.assertj.core.data.Offset.offset(500d));
        assertThat(GeoBox.distanceMeters(0, 0, 0, 1))
                .isCloseTo(111_195d, org.assertj.core.data.Offset.offset(500d));
    }

    @Test
    void boxAroundJakartaContainsCenterAndIsWiderInLongitude() {
        GeoBox box = GeoBox.around(JKT_LAT, JKT_LNG, 50_000d);

        assertThat(box.minLatitude()).isLessThan(JKT_LAT);
        assertThat(box.maxLatitude()).isGreaterThan(JKT_LAT);
        assertThat(box.minLongitude()).isLessThan(JKT_LNG);
        assertThat(box.maxLongitude()).isGreaterThan(JKT_LNG);
        // 纬度 -6.2° 处 cos≈0.994 → 经度跨度略大于纬度跨度。
        assertThat(box.maxLongitude() - box.minLongitude())
                .isGreaterThan(box.maxLatitude() - box.minLatitude());
    }

    /**
     * 🛡 粗筛<b>宁可宽不可窄</b>：半径内的点必须全部落在矩形里。
     * 漏掉的场所是**静默消失**的 —— 列表上看不出少了什么。
     */
    @Test
    void boxNeverExcludesAPointInsideTheRadius() {
        double radius = 50_000d;
        GeoBox box = GeoBox.around(JKT_LAT, JKT_LNG, radius);

        // 东西南北四个方向各取 ~49 km 处的点（略小于半径）。
        double latDelta = Math.toDegrees(49_000d / GeoBox.EARTH_RADIUS_METERS);
        double lngDelta = latDelta / Math.cos(Math.toRadians(JKT_LAT));
        double[][] probes = {
                {JKT_LAT + latDelta, JKT_LNG},
                {JKT_LAT - latDelta, JKT_LNG},
                {JKT_LAT, JKT_LNG + lngDelta},
                {JKT_LAT, JKT_LNG - lngDelta},
        };
        for (double[] p : probes) {
            assertThat(GeoBox.distanceMeters(JKT_LAT, JKT_LNG, p[0], p[1]))
                    .as("探针应在半径内").isLessThan(radius);
            assertThat(p[0]).isBetween(box.minLatitude(), box.maxLatitude());
            assertThat(p[1]).isBetween(box.minLongitude(), box.maxLongitude());
        }
    }

    /** 跨极点：纬度钳到 ±90，经度退回全范围（宁可多捞候选）。 */
    @Test
    void boxNearPoleClampsLatitudeAndGivesUpLongitudeNarrowing() {
        GeoBox box = GeoBox.around(89.9, 10, 50_000d);

        assertThat(box.maxLatitude()).isEqualTo(90d);
        assertThat(box.minLongitude()).isEqualTo(-180d);
        assertThat(box.maxLongitude()).isEqualTo(180d);
    }

    /** 跨对日线：不收窄经度（见 GeoBox 的说明第 2 条）。 */
    @Test
    void boxCrossingAntimeridianGivesUpLongitudeNarrowing() {
        GeoBox box = GeoBox.around(0, 179.9, 50_000d);

        assertThat(box.minLongitude()).isEqualTo(-180d);
        assertThat(box.maxLongitude()).isEqualTo(180d);
        // 纬度仍然收窄 —— 否则这条分支就完全没有粗筛了。
        assertThat(box.maxLatitude() - box.minLatitude()).isLessThan(2d);
    }

    @Test
    void coordinateValidationMatchesDbCheckConstraint() {
        assertThat(GeoBox.isValidCoordinate(-6.2, 106.8)).isTrue();
        assertThat(GeoBox.isValidCoordinate(90, 180)).isTrue();
        assertThat(GeoBox.isValidCoordinate(-90, -180)).isTrue();
        assertThat(GeoBox.isValidCoordinate(90.1, 0)).isFalse();
        assertThat(GeoBox.isValidCoordinate(0, 180.1)).isFalse();
    }
}

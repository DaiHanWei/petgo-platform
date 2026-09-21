package com.tailtopia.place.domain;

/**
 * 经纬度矩形范围（V1.3.0 batch-b1 Story 1.2 · AD-2 Rule 2 的「粗筛」那一半）。
 *
 * <p>纯计算、无依赖 —— 因此距离排序的口径可以在 L0 单测里完整验证（无需 DB）。
 *
 * <h2>🛡 为什么要先粗筛</h2>
 * 直线距离要过三角函数。在 SQL 里对 {@code latitude} / {@code longitude} 套函数，
 * {@code ix_places_active_latitude} / {@code ix_places_active_longitude} 两条索引会<b>全部失效</b>，
 * 查询退化成全表扫描后再排序。所以：<b>范围条件交给索引，距离在应用层算</b>（AD-2 Rule 2/3 的分工）。
 *
 * @param minLatitude  纬度下界（含）
 * @param maxLatitude  纬度上界（含）
 * @param minLongitude 经度下界（含）
 * @param maxLongitude 经度上界（含）
 */
public record GeoBox(double minLatitude, double maxLatitude,
        double minLongitude, double maxLongitude) {

    /** 地球平均半径（米）。WGS84 平均值，直线距离用它足够 —— 本版不做测地线精度。 */
    public static final double EARTH_RADIUS_METERS = 6_371_000d;

    private static final double MIN_LAT = -90d;
    private static final double MAX_LAT = 90d;
    private static final double MIN_LNG = -180d;
    private static final double MAX_LNG = 180d;

    /**
     * 以 ({@code lat}, {@code lng}) 为中心、边长约 {@code 2 × radiusMeters} 的矩形。
     *
     * <h2>两处有意的近似</h2>
     * <ol>
     *   <li><b>纬度跨极点时钳到 ±90</b>：钳完矩形比圆大，只会多捞候选、不会漏 —— 粗筛宁可宽不可窄，
     *       漏掉的那个场所是<b>静默消失</b>的，没人能从列表上看出来。</li>
     *   <li><b>经度跨 ±180 时整条经度不再收窄</b>（退化成全经度范围）：此时靠纬度索引 + 应用层距离
     *       仍然得到正确结果，只是候选多一些。印尼离对日线很远，这条分支实际走不到，
     *       但写成「拆成两段 OR」会让那条 SQL 复杂一倍去服务一个不存在的场景。</li>
     * </ol>
     */
    public static GeoBox around(double lat, double lng, double radiusMeters) {
        double latDelta = Math.toDegrees(radiusMeters / EARTH_RADIUS_METERS);
        double minLat = lat - latDelta;
        double maxLat = lat + latDelta;

        // 纬度越接近两极，同样的米数对应的经度差越大（cos 收缩）。cos 趋 0 时除法会炸 →
        // 直接放弃经度收窄（退回全范围），正确性不受影响。
        double cos = Math.cos(Math.toRadians(lat));
        double minLng;
        double maxLng;
        if (minLat <= MIN_LAT || maxLat >= MAX_LAT || Math.abs(cos) < 1e-9) {
            minLng = MIN_LNG;
            maxLng = MAX_LNG;
        } else {
            double lngDelta = latDelta / cos;
            minLng = lng - lngDelta;
            maxLng = lng + lngDelta;
            if (minLng < MIN_LNG || maxLng > MAX_LNG) {
                // 跨对日线：不收窄经度（见上面第 2 条）。
                minLng = MIN_LNG;
                maxLng = MAX_LNG;
            }
        }
        return new GeoBox(
                Math.max(MIN_LAT, minLat), Math.min(MAX_LAT, maxLat), minLng, maxLng);
    }

    /**
     * 两点间直线距离（米，haversine）。
     *
     * <p>🛡 **不接第三方 LBS、不装地理扩展**（AD-2 Rule 4）—— 距离就是这 6 行数学。
     */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1d, Math.sqrt(a)));
    }

    /** 坐标是否在合法区间内（与 {@code ck_places_latitude/longitude} 同一口径）。 */
    public static boolean isValidCoordinate(double lat, double lng) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG;
    }
}

package com.tailtopia.analytics.rollup;

import java.time.Duration;

/**
 * 购物车停留时长分桶（analytics-pipeline Story 4.6，电商 #7）：{@code agg_daily_cart} 的 4 个 {@code dwell_*} 列。
 *
 * <p>停留分钟 = {@code floor((t_end − t_add) 秒 / 60)}（不足一分钟舍去）；下界含、上界不含。观察窗最长约 8 天，
 * 超过 7 天的也落在最后一桶。{@code cart.sql} 用同一组阈值（60 / 1440 / 4320 分钟），由 L0 测试守住两边一致；
 * 「仍在车里」的件不入分桶（只进中位数）。
 */
public enum CartDwellBucket {

    /** {@code < 1 小时}。 */
    LT_1H("dwell_lt_1h", 0),
    /** {@code [1 小时, 1 天)}。 */
    H1_D1("dwell_1h_1d", 60),
    /** {@code [1 天, 3 天)}。 */
    D1_D3("dwell_1d_3d", 24 * 60),
    /** {@code ≥ 3 天}（含超过 7 天）。 */
    D3_D7("dwell_3d_7d", 3 * 24 * 60);

    private final String column;
    private final int lowerMinutes;

    CartDwellBucket(String column, int lowerMinutes) {
        this.column = column;
        this.lowerMinutes = lowerMinutes;
    }

    /** {@code agg_daily_cart} 的列名。 */
    public String column() {
        return column;
    }

    /** 下界（分钟，含）。 */
    public int lowerMinutes() {
        return lowerMinutes;
    }

    /** 停留分钟（{@code ≥ 0}）所属的桶。负数视为 0。 */
    public static CartDwellBucket ofMinutes(long minutes) {
        CartDwellBucket out = LT_1H;
        for (CartDwellBucket b : values()) {
            if (minutes >= b.lowerMinutes) {
                out = b;
            }
        }
        return out;
    }

    /** 停留时长所属的桶：先按 SQL 的写法折成整分钟（舍去秒）。 */
    public static CartDwellBucket of(Duration dwell) {
        return ofMinutes(dwell.toMinutes());
    }
}

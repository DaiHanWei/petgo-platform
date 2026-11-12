package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * L0：购物车停留时长分桶边界（analytics-pipeline Story 4.6 T7）：59 分 / 60 分 / 24 小时 / 3 天 / 7 天，
 * 以及 {@code cart.sql} 用的是同一组阈值。
 */
class CartDwellBucketTest {

    @Test
    void boundaries() {
        assertThat(CartDwellBucket.of(Duration.ZERO)).isEqualTo(CartDwellBucket.LT_1H);
        assertThat(CartDwellBucket.of(Duration.ofMinutes(59))).isEqualTo(CartDwellBucket.LT_1H);
        // 59 分 59 秒按整分钟舍去，仍 < 1 小时
        assertThat(CartDwellBucket.of(Duration.ofMinutes(59).plusSeconds(59))).isEqualTo(CartDwellBucket.LT_1H);
        assertThat(CartDwellBucket.of(Duration.ofMinutes(60))).isEqualTo(CartDwellBucket.H1_D1);
        assertThat(CartDwellBucket.of(Duration.ofHours(24).minusMinutes(1))).isEqualTo(CartDwellBucket.H1_D1);
        assertThat(CartDwellBucket.of(Duration.ofHours(24))).isEqualTo(CartDwellBucket.D1_D3);
        assertThat(CartDwellBucket.of(Duration.ofDays(3).minusMinutes(1))).isEqualTo(CartDwellBucket.D1_D3);
        assertThat(CartDwellBucket.of(Duration.ofDays(3))).isEqualTo(CartDwellBucket.D3_D7);
        assertThat(CartDwellBucket.of(Duration.ofDays(7))).isEqualTo(CartDwellBucket.D3_D7);
        // 观察窗最长约 8 天（加购日 0 点加购、窗口到加购日 + 8 天 0 点），超过 7 天也在最后一桶
        assertThat(CartDwellBucket.of(Duration.ofDays(8))).isEqualTo(CartDwellBucket.D3_D7);
        assertThat(CartDwellBucket.ofMinutes(-5)).isEqualTo(CartDwellBucket.LT_1H);
    }

    @Test
    void columnsMatchMigration() {
        assertThat(CartDwellBucket.values()).extracting(CartDwellBucket::column)
                .containsExactly("dwell_lt_1h", "dwell_1h_1d", "dwell_1d_3d", "dwell_3d_7d");
        assertThat(CartDwellBucket.values()).extracting(CartDwellBucket::lowerMinutes)
                .containsExactly(0, 60, 1440, 4320);
    }

    /** cart.sql 的分桶条件与 Java 阈值一致（两处都写在 TOTAL / PRODUCT 两个聚合里）；IN_CART 不入分桶。 */
    @Test
    void sqlUsesSameThresholds() {
        String sql = SqlRollupFamily.readScript(CartFamily.NAME);
        String[] expected = {
            "i.outcome <> 'IN_CART' AND i.dwell_minutes < 60) AS dwell_lt_1h",
            "i.outcome <> 'IN_CART' AND i.dwell_minutes >= 60 AND i.dwell_minutes < 1440)",
            "i.outcome <> 'IN_CART' AND i.dwell_minutes >= 1440 AND i.dwell_minutes < 4320)",
            "i.outcome <> 'IN_CART' AND i.dwell_minutes >= 4320) AS dwell_3d_7d"};
        for (String e : expected) {
            assertThat(sql.split(java.util.regex.Pattern.quote(e), -1)).as(e).hasSize(3);
        }
        assertThat(sql).contains("CAST(floor(extract(epoch FROM (i.t_end - i.t_add)) / 60) AS integer)");
    }
}

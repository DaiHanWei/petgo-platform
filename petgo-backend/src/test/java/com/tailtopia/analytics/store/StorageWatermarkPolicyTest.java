package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.tailtopia.analytics.store.StorageWatermarkPolicy.Evaluation;
import com.tailtopia.analytics.store.StorageWatermarkPolicy.Snapshot;
import com.tailtopia.analytics.store.StorageWatermarkPolicy.Thresholds;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：analytics-pipeline Story 1.10 AC2 / AC4 —— 水位评估纯函数。 */
class StorageWatermarkPolicyTest {

    private static final long GB = 1024L * 1024 * 1024;
    private static final long ALERT = 40 * GB;
    private static final Thresholds T = new Thresholds(ALERT, 60_000_000L, 3);
    private static final LocalDate TODAY = LocalDate.of(2026, 12, 15);

    private static long gb(double v) {
        return Math.round(v * GB);
    }

    @Test
    @DisplayName("39.9 GB 不超线、无提示；40.1 GB 超线且同时提示切回")
    void alertThreshold() {
        Evaluation below = StorageWatermarkPolicy.evaluate(gb(39.9), 0, null, T);
        assertThat(below.overAlert()).isFalse();
        assertThat(below.switchBackHint()).isFalse();
        assertThat(below.monthsToAlert()).isNull();

        Evaluation above = StorageWatermarkPolicy.evaluate(gb(40.1), 0, null, T);
        assertThat(above.overAlert()).isTrue();
        assertThat(above.switchBackHint()).isTrue();
        assertThat(above.monthsToAlert()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("恰好 40 GB 不算超过")
    void exactlyAtAlertIsNotOver() {
        assertThat(StorageWatermarkPolicy.evaluate(ALERT, 0, null, T).overAlert()).isFalse();
    }

    @Test
    @DisplayName("月事件 5,999 万不提示；6,000 万提示")
    void monthEventsHint() {
        assertThat(StorageWatermarkPolicy.evaluate(gb(10), 59_990_000L, null, T).switchBackHint()).isFalse();
        Evaluation hit = StorageWatermarkPolicy.evaluate(gb(10), 60_000_000L, null, T);
        assertThat(hit.switchBackHint()).isTrue();
        assertThat(hit.overAlert()).isFalse();
    }

    @Test
    @DisplayName("增速外推 2.9 个月提示；3.1 个月不提示")
    void growthExtrapolation() {
        long db = gb(10);
        long remaining = ALERT - db;
        Evaluation fast = StorageWatermarkPolicy.evaluate(db, 0, Math.round(remaining / 2.9), T);
        assertThat(fast.monthsToAlert()).isCloseTo(2.9, within(0.001));
        assertThat(fast.switchBackHint()).isTrue();

        Evaluation slow = StorageWatermarkPolicy.evaluate(db, 0, Math.round(remaining / 3.1), T);
        assertThat(slow.monthsToAlert()).isCloseTo(3.1, within(0.001));
        assertThat(slow.switchBackHint()).isFalse();
    }

    @Test
    @DisplayName("增速为 0 或负：不外推、不提示")
    void zeroOrNegativeGrowth() {
        Evaluation zero = StorageWatermarkPolicy.evaluate(gb(39), 0, 0L, T);
        assertThat(zero.switchBackHint()).isFalse();
        assertThat(zero.monthsToAlert()).isNull();
        Evaluation negative = StorageWatermarkPolicy.evaluate(gb(39), 0, -gb(1), T);
        assertThat(negative.switchBackHint()).isFalse();
        assertThat(negative.monthsToAlert()).isNull();
    }

    @Test
    @DisplayName("增长：恰有 30 天前快照 → 直接相减")
    void growthWithThirtyDayReference() {
        List<Snapshot> prior = List.of(new Snapshot(TODAY.minusDays(30), gb(5)), new Snapshot(TODAY.minusDays(40), gb(1)));
        assertThat(StorageWatermarkPolicy.monthGrowthBytes(TODAY, gb(8), prior)).isEqualTo(gb(8) - gb(5));
    }

    @Test
    @DisplayName("增长：30 天前那天缺快照 → 取更早里最近的一条按天数折算为 30 天")
    void growthWithGapBeforeCutoff() {
        List<Snapshot> prior = List.of(new Snapshot(TODAY.minusDays(32), gb(4)), new Snapshot(TODAY.minusDays(60), 0));
        // 32 天涨 4 GB → 30 天折算 3.75 GB
        assertThat(StorageWatermarkPolicy.monthGrowthBytes(TODAY, gb(8), prior)).isEqualTo(Math.round(4.0 * GB * 30 / 32));
    }

    @Test
    @DisplayName("增长：历史不足 30 天 → 取最早快照按天数折算为 30 天")
    void growthWithShortHistory() {
        List<Snapshot> prior = List.of(new Snapshot(TODAY.minusDays(10), gb(2)), new Snapshot(TODAY.minusDays(3), gb(2.5)));
        // 10 天涨 1 GB → 30 天 3 GB
        assertThat(StorageWatermarkPolicy.monthGrowthBytes(TODAY, gb(3), prior)).isEqualTo(gb(3));
    }

    @Test
    @DisplayName("增长：没有更早快照（仅当天一条）→ null；当天自身的旧值被忽略")
    void growthWithoutHistory() {
        assertThat(StorageWatermarkPolicy.monthGrowthBytes(TODAY, gb(3), List.of())).isNull();
        assertThat(StorageWatermarkPolicy.monthGrowthBytes(TODAY, gb(3), List.of(new Snapshot(TODAY, gb(1))))).isNull();
    }

    @Test
    @DisplayName("增长可为负（删分区后），负增长不触发提示")
    void negativeGrowthFromHistory() {
        Long growth = StorageWatermarkPolicy.monthGrowthBytes(TODAY, gb(20),
                List.of(new Snapshot(TODAY.minusDays(30), gb(30))));
        assertThat(growth).isEqualTo(-gb(10));
        assertThat(StorageWatermarkPolicy.evaluate(gb(20), 0, growth, T).switchBackHint()).isFalse();
    }
}

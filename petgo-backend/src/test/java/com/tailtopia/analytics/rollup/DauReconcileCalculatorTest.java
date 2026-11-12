package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.analytics.rollup.DauReconcileCalculator.Input;
import com.tailtopia.analytics.rollup.DauReconcileCalculator.Result;
import com.tailtopia.analytics.rollup.DauReconcileCalculator.Status;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：日活对账纯计算（analytics-pipeline Story 4.2 AC3 / AC4，FR-25）。 */
class DauReconcileCalculatorTest {

    private static final LocalDate D0 = LocalDate.of(2026, 11, 1);

    private static Input in(int dayOffset, long self, Long ga4New, Long ga4All) {
        return new Input(D0.plusDays(dayOffset), self, ga4New, ga4All);
    }

    private static Result at(List<Result> rs, int dayOffset) {
        LocalDate d = D0.plusDays(dayOffset);
        return rs.stream().filter(r -> r.statDate().equals(d)).findFirst().orElseThrow();
    }

    @Test
    void diffAndCoverageAreDecimalsWithFourPlaces() {
        assertThat(DauReconcileCalculator.diffPct(1052, 1000L)).isEqualByComparingTo("0.0520");
        assertThat(DauReconcileCalculator.diffPct(1052, 1000L).scale()).isEqualTo(4);
        assertThat(DauReconcileCalculator.diffPct(900, 1000L)).isEqualByComparingTo("-0.1000");
        assertThat(DauReconcileCalculator.diffPct(1, 3L)).isEqualByComparingTo("-0.6667");
        assertThat(DauReconcileCalculator.coverage(900L, 1000L)).isEqualByComparingTo("0.9000");
        assertThat(DauReconcileCalculator.coverage(2L, 3L)).isEqualByComparingTo("0.6667");
    }

    @Test
    void ga4ZeroOrMissingDoesNotDivideByZero() {
        assertThat(DauReconcileCalculator.diffPct(10, 0L)).isNull();
        assertThat(DauReconcileCalculator.diffPct(10, null)).isNull();
        assertThat(DauReconcileCalculator.coverage(0L, 0L)).isNull();
        assertThat(DauReconcileCalculator.coverage(null, 100L)).isNull();
        assertThat(DauReconcileCalculator.coverage(5L, null)).isNull();
    }

    @Test
    void coverageBelow90NeverStarts() {
        List<Result> rs = DauReconcileCalculator.compute(List.of(
                in(0, 100, 50L, 100L), in(1, 100, 89L, 100L), in(2, 100, 8999L, 10000L)));
        assertThat(rs).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(Status.NOT_STARTED);
            assertThat(r.observationStart()).isNull();
        });
        assertThat(at(rs, 2).coverage()).isEqualByComparingTo("0.8999");
    }

    @Test
    void observationWindowIs28DaysInclusive() {
        List<Input> rows = new ArrayList<>();
        rows.add(in(0, 100, 80L, 100L));  // 覆盖率 0.80：观察期之前
        rows.add(in(1, 105, 100L, 110L)); // 0.9091 ≥ 0.90：起点；差值 +5%
        rows.add(in(2, 120, 100L, 100L)); // 期内 +20% → 异常
        rows.add(in(3, 89, 100L, 100L));  // 期内 −11% → 异常
        rows.add(in(4, 110, 100L, 100L)); // 恰好 +10% → 正常
        rows.add(in(5, 90, 100L, 100L));  // 恰好 −10% → 正常
        rows.add(in(6, 100, 100L, 50L));  // 覆盖率之后掉下去也不影响（起点只看第一次）
        rows.add(in(28, 300, 100L, 100L)); // 起点 + 27 = 期内最后一天 → 异常
        rows.add(in(29, 100, 100L, 100L)); // 第 29 天起 → 已结束
        rows.add(in(40, 100, null, null)); // 已结束优先于 GA4 缺数
        List<Result> rs = DauReconcileCalculator.compute(rows);

        assertThat(rs).allSatisfy(r -> assertThat(r.observationStart()).isEqualTo(D0.plusDays(1)));
        assertThat(at(rs, 0).status()).isEqualTo(Status.NOT_STARTED);
        assertThat(at(rs, 1).status()).isEqualTo(Status.NORMAL);
        assertThat(at(rs, 1).diffPct()).isEqualByComparingTo("0.0500");
        assertThat(at(rs, 1).coverage()).isEqualByComparingTo("0.9091");
        assertThat(at(rs, 2).status()).isEqualTo(Status.ABNORMAL);
        assertThat(at(rs, 3).status()).isEqualTo(Status.ABNORMAL);
        assertThat(at(rs, 4).status()).isEqualTo(Status.NORMAL);
        assertThat(at(rs, 5).status()).isEqualTo(Status.NORMAL);
        assertThat(at(rs, 6).status()).isEqualTo(Status.NORMAL);
        assertThat(at(rs, 28).status()).isEqualTo(Status.ABNORMAL);
        assertThat(at(rs, 29).status()).isEqualTo(Status.ENDED);
        assertThat(at(rs, 40).status()).isEqualTo(Status.ENDED);
    }

    @Test
    void exactlyTenPercentIsNormalAfterRounding() {
        // 0.10004 四舍五入为 0.1000 → 正常；0.10005 → 0.1001 → 异常（比较用表里看得到的四位小数）
        List<Result> rs = DauReconcileCalculator.compute(List.of(
                in(0, 100, 100L, 100L), in(1, 110004, 100000L, 100000L), in(2, 110005, 100000L, 100000L)));
        assertThat(at(rs, 1).diffPct()).isEqualByComparingTo("0.1000");
        assertThat(at(rs, 1).status()).isEqualTo(Status.NORMAL);
        assertThat(at(rs, 2).diffPct()).isEqualByComparingTo("0.1001");
        assertThat(at(rs, 2).status()).isEqualTo(Status.ABNORMAL);
    }

    @Test
    void missingGa4IsNoGa4InsideAndBeforeWindow() {
        List<Result> rs = DauReconcileCalculator.compute(List.of(
                in(0, 100, null, null), in(1, 100, 95L, 100L), in(2, 100, null, null)));
        assertThat(at(rs, 0).status()).isEqualTo(Status.NO_GA4);
        assertThat(at(rs, 0).coverage()).isNull();
        assertThat(at(rs, 0).diffPct()).isNull();
        assertThat(at(rs, 1).status()).isEqualTo(Status.NORMAL);
        assertThat(at(rs, 2).status()).isEqualTo(Status.NO_GA4);
    }

    @Test
    void stagWithoutGa4IsAllNoGa4() {
        List<Result> rs = DauReconcileCalculator.compute(List.of(in(0, 5, null, null), in(1, 7, null, null)));
        assertThat(rs).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(Status.NO_GA4);
            assertThat(r.observationStart()).isNull();
        });
    }

    @Test
    void ga4NewVersionZeroInsideWindow() {
        List<Result> rs = DauReconcileCalculator.compute(List.of(
                in(0, 100, 100L, 100L), in(1, 0, 0L, 100L), in(2, 3, 0L, 100L)));
        assertThat(at(rs, 1).diffPct()).isNull();
        assertThat(at(rs, 1).status()).isEqualTo(Status.NORMAL);
        assertThat(at(rs, 2).diffPct()).isNull();
        assertThat(at(rs, 2).status()).isEqualTo(Status.ABNORMAL);
    }

    @Test
    void startMovesWhenGa4IsRefetched() {
        List<Input> before = List.of(in(0, 100, 85L, 100L), in(1, 100, 92L, 100L));
        assertThat(DauReconcileCalculator.compute(before).get(0).observationStart()).isEqualTo(D0.plusDays(1));
        // GA4 回拉后 D0 覆盖率变成 0.90 → 起点前移
        List<Input> after = List.of(in(0, 100, 90L, 100L), in(1, 100, 92L, 100L));
        List<Result> rs = DauReconcileCalculator.compute(after);
        assertThat(rs.get(0).observationStart()).isEqualTo(D0);
        assertThat(rs.get(0).status()).isEqualTo(Status.ABNORMAL); // 100 vs 90 → +11.11%
    }

    @Test
    void unsortedInputIsSortedAndEmptyIsEmpty() {
        List<Result> rs = DauReconcileCalculator.compute(List.of(in(2, 1, 1L, 1L), in(0, 1, 1L, 1L)));
        assertThat(rs).extracting(Result::statDate).containsExactly(D0, D0.plusDays(2));
        assertThat(DauReconcileCalculator.compute(List.of())).isEmpty();
        assertThat(DauReconcileCalculator.COVERAGE_THRESHOLD).isEqualByComparingTo(new BigDecimal("0.9"));
    }
}

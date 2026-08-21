package com.tailtopia.shop.repurchase;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.domain.FeedingGuideEntry;
import com.tailtopia.shop.repurchase.domain.DepletionForecast;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：🔴 粮量见底预估公式（Story 6.3，FR-109，<b>S-14 三项修正</b>）。
 *
 * <p>🔴 <b>这三项原本是「输入齐全但算错」</b> —— {@code 缺输入不触发} 的降级策略拦不住它们。
 * 算错的结果和算对的结果长得一模一样，只是日期偏了，上线后没人会发现。
 */
class DepletionForecastTest {

    private static final List<FeedingGuideEntry> GUIDE = List.of(
            new FeedingGuideEntry(0, 9, 80),
            new FeedingGuideEntry(10, 25, 110),
            new FeedingGuideEntry(26, 60, 200));

    private static final LocalDate DELIVERED = LocalDate.of(2026, 8, 1);

    // ---------- 🔴 修正①：漏乘购买数量 ----------

    @Test
    @DisplayName("🔴 S-14 修正①：qty=3 时可用天数恰为单件的 3 倍（囤货是宠粮最主流的购买行为）")
    void quantityMultipliesAvailableDays() {
        // 3000 g / 110 g/天 = 27 天
        LocalDate one = DepletionForecast.estimateDepletionDate(GUIDE, kg(15), 3000L, 1, DELIVERED);
        // 9000 g / 110 g/天 = 81 天
        LocalDate three =
                DepletionForecast.estimateDepletionDate(GUIDE, kg(15), 3000L, 3, DELIVERED);

        assertThat(one).isEqualTo(DELIVERED.plusDays(27));
        assertThat(three)
                .as("🔴 买 3 袋按 1 袋算，提醒会在还剩 2 袋时就发出来")
                .isEqualTo(DELIVERED.plusDays(81));
        assertThat(java.time.temporal.ChronoUnit.DAYS.between(DELIVERED, three))
                .isEqualTo(3 * java.time.temporal.ChronoUnit.DAYS.between(DELIVERED, one));
    }

    // ---------- 🔴 修正②：起算点是送达日不是下单日 ----------

    @Test
    @DisplayName("🔴 S-14 修正②：送达日晚于下单日 3 天时耗尽日相应后移 3 天")
    void depletionStartsFromDeliveryNotOrder() {
        LocalDate placedOn = LocalDate.of(2026, 8, 1);
        LocalDate deliveredOn = placedOn.plusDays(3);

        LocalDate fromOrder =
                DepletionForecast.estimateDepletionDate(GUIDE, kg(15), 3000L, 1, placedOn);
        LocalDate fromDelivery =
                DepletionForecast.estimateDepletionDate(GUIDE, kg(15), 3000L, 1, deliveredOn);

        assertThat(java.time.temporal.ChronoUnit.DAYS.between(fromOrder, fromDelivery))
                .as("🔴 快递 2–4 日而提前量只有 7 天 —— 用下单日会让提醒早到近一半窗口")
                .isEqualTo(3);
    }

    // ---------- 触发时点 ----------

    @Test
    @DisplayName("耗尽日前 7 天开始触发；第 8 天前不触发")
    void triggersSevenDaysAhead() {
        LocalDate depletion = LocalDate.of(2026, 9, 1);
        assertThat(DepletionForecast.triggerDateFor(depletion))
                .isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(DepletionForecast.shouldTriggerOn(depletion, LocalDate.of(2026, 8, 24)))
                .isFalse();
        assertThat(DepletionForecast.shouldTriggerOn(depletion, LocalDate.of(2026, 8, 25)))
                .isTrue();
        // 已过预估耗尽日仍然触发 —— 粮真没了才更该提醒
        assertThat(DepletionForecast.shouldTriggerOn(depletion, LocalDate.of(2026, 9, 5))).isTrue();
    }

    // ---------- 🔴 缺输入静默（一等路径，不是异常路径） ----------

    @Test
    @DisplayName("🔴 商品未配日喂量（DEP-6 未到位）→ 静默返回 null，不猜、不报错")
    void missingFeedingGuideIsSilent() {
        assertThat(DepletionForecast.estimateDepletionDate(null, kg(15), 3000L, 1, DELIVERED))
                .isNull();
        assertThat(DepletionForecast.estimateDepletionDate(List.of(), kg(15), 3000L, 1, DELIVERED))
                .isNull();
    }

    @Test
    @DisplayName("🔴 档案无体重 → 静默")
    void missingWeightIsSilent() {
        assertThat(DepletionForecast.estimateDepletionDate(GUIDE, null, 3000L, 1, DELIVERED))
                .isNull();
    }

    @Test
    @DisplayName("🔴 SKU 无净含量 → 静默")
    void missingNetWeightIsSilent() {
        assertThat(DepletionForecast.estimateDepletionDate(GUIDE, kg(15), null, 1, DELIVERED))
                .isNull();
        assertThat(DepletionForecast.estimateDepletionDate(GUIDE, kg(15), 0L, 1, DELIVERED))
                .isNull();
    }

    @Test
    @DisplayName("🔴 未送达（无送达日）→ 静默 —— 还没到手的粮没有开始吃")
    void missingDeliveryDateIsSilent() {
        assertThat(DepletionForecast.estimateDepletionDate(GUIDE, kg(15), 3000L, 1, null))
                .isNull();
    }

    @Test
    @DisplayName("🔴 体重落在所有区间之外 → 静默，不取最近区间外推（外推出来的数字是编的）")
    void weightOutsideAllRangesIsSilent() {
        assertThat(DepletionForecast.dailyGramsFor(GUIDE, kg(100))).isNull();
        assertThat(DepletionForecast.estimateDepletionDate(GUIDE, kg(100), 3000L, 1, DELIVERED))
                .isNull();
    }

    // ---------- 区间查表 ----------

    @Test
    @DisplayName("日喂量按体重区间查表，边界值含在区间内")
    void dailyGramsLookupIsInclusive() {
        assertThat(DepletionForecast.dailyGramsFor(GUIDE, kg(0.5))).isEqualTo(80);
        assertThat(DepletionForecast.dailyGramsFor(GUIDE, kg(9))).isEqualTo(80);
        assertThat(DepletionForecast.dailyGramsFor(GUIDE, kg(10))).isEqualTo(110);
        assertThat(DepletionForecast.dailyGramsFor(GUIDE, kg(25))).isEqualTo(110);
        assertThat(DepletionForecast.dailyGramsFor(GUIDE, kg(26))).isEqualTo(200);
    }

    @Test
    @DisplayName("一天都撑不到的量不触发 —— 那不是复购提醒该管的情形")
    void lessThanOneDayIsSilent() {
        assertThat(DepletionForecast.estimateDepletionDate(GUIDE, kg(15), 50L, 1, DELIVERED))
                .isNull();
    }

    private static BigDecimal kg(double v) {
        return BigDecimal.valueOf(v);
    }
}

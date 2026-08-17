package com.tailtopia.shop.repurchase.domain;

import com.tailtopia.shop.domain.FeedingGuideEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 🔴 粮量见底预估（Story 6.3，FR-109，<b>S-14 三项修正全部落在这里</b>）。
 *
 * <pre>
 *   日喂量  ← 按体重在商品的喂量区间表里查
 *   可用天数 = (净含量 × qty) ÷ 日喂量        ← 修正① 原公式【漏乘购买数量】
 *   耗尽日   = 订单【送达日】 + 可用天数        ← 修正② 原用下单日
 *   触发日   = 耗尽日 − 7 天
 * </pre>
 *
 * <h2>为什么这三项必须在实现时就正确</h2>
 * 🔴 它们是<b>「输入齐全但算错」</b>——{@code 缺输入不触发} 的降级策略<b>拦不住</b>它们。
 * 算错的结果和算对的结果长得一模一样，只是日期偏了：
 * <ul>
 *   <li><b>修正①</b>：囤货是宠粮最主流的购买行为。买 3 袋按 1 袋算，提醒会在还剩 2 袋时就发出来 ——
 *       用户会觉得这个提醒很蠢，然后再也不看它。</li>
 *   <li><b>修正②</b>：快递 2–4 日，而提前量只有 7 天。用下单日会让提醒早到近一半窗口。</li>
 *   <li><b>修正③</b>（多宠物共食）：<b>L-11 的单账号单宠物硬约束仍在</b>
 *       （{@code ProfileService} 以 {@code existsByOwnerId} → 409），该场景<b>结构上不可能发生</b>。
 *       ⚠️ <b>1.3.0 多宠物后需重算日喂量口径</b>（多只共食时日喂量应为各宠物之和）。</li>
 * </ul>
 *
 * <h2>缺输入是常态，不是异常</h2>
 * 🔴 无购买历史 / 商品未配日喂量 / 档案无体重 → <b>静默不触发，不做兜底猜测、不报错</b>。
 * 按当前 <b>DEP-6</b> 状态（每日建议喂量数据未到位），<b>上线首日极可能对全体用户不触发</b> ——
 * 这条路径必须按一等路径对待。
 */
public final class DepletionForecast {

    /** 耗尽日前多少天触发（FR-109）。 */
    public static final int LEAD_DAYS = 7;

    private DepletionForecast() {
    }

    /**
     * 估算耗尽日。
     *
     * @param feedingGuide 商品的喂量区间表（DEP-6 数据）。null / 空 → 不触发
     * @param weightKg     宠物体重。null → 不触发
     * @param netWeightG   SKU 净含量（克）。null / ≤0 → 不触发
     * @param qty          🔴 <b>购买数量</b>（修正①）
     * @param deliveredOn  🔴 <b>订单送达日</b>（修正②），null → 不触发
     * @return 耗尽日；任一输入缺失或不可用时返回 {@code null}（静默不触发）
     */
    public static LocalDate estimateDepletionDate(List<FeedingGuideEntry> feedingGuide,
            BigDecimal weightKg, Long netWeightG, int qty, LocalDate deliveredOn) {
        Integer gramsPerDay = dailyGramsFor(feedingGuide, weightKg);
        if (gramsPerDay == null || gramsPerDay <= 0) {
            return null;    // 商品未配喂量 / 体重不在任何区间 → 静默
        }
        if (netWeightG == null || netWeightG <= 0 || qty <= 0 || deliveredOn == null) {
            return null;
        }
        // 🔴 修正①：净含量乘以购买数量。囤货是宠粮最主流的购买行为。
        long totalGrams = netWeightG * (long) qty;
        // 整数除法向下取整 —— 宁可早一天提醒，也不要等粮真的断了才提醒
        long days = totalGrams / gramsPerDay;
        if (days <= 0) {
            return null;    // 一天都撑不到：这不是复购提醒该管的情形
        }
        // 🔴 修正②：以【送达日】起算，不是下单日
        return deliveredOn.plusDays(days);
    }

    /** 触发日 = 耗尽日 − 7 天。 */
    public static LocalDate triggerDateFor(LocalDate depletionDate) {
        return depletionDate == null ? null : depletionDate.minusDays(LEAD_DAYS);
    }

    /** 今天是否该为这条预估生成触发记录（到点或已过点都算）。 */
    public static boolean shouldTriggerOn(LocalDate depletionDate, LocalDate today) {
        LocalDate triggerOn = triggerDateFor(depletionDate);
        return triggerOn != null && today != null && !today.isBefore(triggerOn);
    }

    /**
     * 按体重在喂量区间表里查日喂量（克/天）。
     *
     * <p>🔴 <b>体重落在任何区间之外就返回 null（静默不触发）</b>，
     * 不取最近的区间外推 —— 外推出来的数字看起来同样合理，但它是编的。
     */
    public static Integer dailyGramsFor(List<FeedingGuideEntry> guide, BigDecimal weightKg) {
        if (guide == null || guide.isEmpty() || weightKg == null) {
            return null;
        }
        double kg = weightKg.doubleValue();
        for (FeedingGuideEntry e : guide) {
            if (kg >= e.weightMinKg() && kg <= e.weightMaxKg()) {
                return e.gramsPerDay();
            }
        }
        return null;
    }
}

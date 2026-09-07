package com.tailtopia.admin.config.dto;

/**
 * 分享奖励配置页的**同屏对照数据**（V1.1.6 Story 18.3 · AC2/AC3）。
 *
 * <h2>🔴 为什么白嫖倍数必须同屏（OQ-C1）</h2>
 * 「月度上限 30 枚」和「HD 解锁 60 枚」<b>分开看都合理</b>，放一起才看得出
 * 「两个月就能白嫖一次 HD」。只看 30 这个数，看不出这件事。
 * 所以本 record 把 HD 价与倍数一起算出来交给模板，而不是让运营自己心算。
 *
 * <h2>⚠️ 为什么还要当月消耗（AC3）</h2>
 * 不给「已发放总量 / 达上限账号数」，运营改这个数就是拍脑袋。
 */
public record ShareRewardOverview(
        /** 当前 HD 解锁价（IDR；PawCoin 与 IDR 1:1）。 */
        long hdPrice,
        /** 月度上限。 */
        long monthlyCap,
        /**
         * 攒满几个月额度可换一次 HD 解锁，保留一位小数。
         *
         * <p>⚠️ {@code monthlyCap == 0} 时返回 {@code null} —— 那表示**不发**，
         * 「几个月能换一次」这个问题不成立。
         * 🔴 这里绝不能算成 0 或 Infinity：0 会被读成「立刻就能白嫖」，
         * 正好与事实相反。
         */
        Double monthsPerHdUnlock,
        /** 本 WIB 自然月已发放总量。 */
        long grantedThisMonth,
        /** 本 WIB 自然月达到上限的账号数。 */
        long accountsAtCap,
        /** WIB 月份标识（YYYY-MM），供界面显式标注口径。 */
        String period) {

    /** 倍数计算是纯函数，单独暴露以便 L0 直接测（含 cap=0 的除零边界）。 */
    public static Double monthsPerHdUnlock(long hdPrice, long monthlyCap) {
        if (monthlyCap <= 0) {
            return null; // 不发 ⇒ 这个问题不成立
        }
        double months = (double) hdPrice / (double) monthlyCap;
        return Math.round(months * 10) / 10.0;
    }
}

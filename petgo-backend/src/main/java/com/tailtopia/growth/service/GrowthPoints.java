package com.tailtopia.growth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 按金额换算成长值（V1.4.0 ① 架构 §5.3）：{@code round(amount × points / unitAmount)}，{@code BigDecimal} + HALF_UP。 */
public final class GrowthPoints {

    private GrowthPoints() {
    }

    private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);

    /** 金额（IDR，1 PawCoin = Rp1）换算成长值；非正金额 / 无效配置 → 0；异常大额截断到 int 上限，不抛。 */
    public static int ofAmount(long amount, int points, Integer unitAmount) {
        if (amount <= 0 || points <= 0 || unitAmount == null || unitAmount <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(points))
                .divide(BigDecimal.valueOf(unitAmount), 0, RoundingMode.HALF_UP)
                .min(INT_MAX)
                .intValue();
    }
}

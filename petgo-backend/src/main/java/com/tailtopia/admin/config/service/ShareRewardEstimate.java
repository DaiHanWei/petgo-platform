package com.tailtopia.admin.config.service;

import com.tailtopia.admin.config.dto.ShareRewardForm;
import java.time.Instant;
import java.time.YearMonth;

/**
 * 分享奖励「每个账号每月最多可获得」估算（V1.4.2 shop-admin Story 3.2 · AD-8 · C2）。纯函数：
 * {@code min(月度上限, Σ(每次发放 × 每日上限) × 当月天数)}，Σ 取四个渠道（身份证 / 年龄卡 / Tailsonality / 护照），
 * 当月天数按 <b>WIB</b> 自然月（大月 31、小月 30、2 月 28/29）。月度上限 0 = 不发 ⇒ 结果 0。
 */
public final class ShareRewardEstimate {

    private ShareRewardEstimate() {
    }

    /** 四渠道每日可得合计 {@code Σ(每次发放 × 每日上限)}。 */
    public static long dailySum(ShareRewardForm f) {
        return f.idCardShareReward() * f.idCardShareDailyCap()
                + f.ageCardShareReward() * f.ageCardShareDailyCap()
                + f.tailsonalityShareReward() * f.tailsonalityShareDailyCap()
                + f.passportShareReward() * f.passportShareDailyCap();
    }

    /** 该月（WIB）的天数。 */
    public static int daysOf(YearMonth month) {
        return month.lengthOfMonth();
    }

    /** 估算值（公式本身，不看总开关）。 */
    public static long monthlyMaxPerAccount(ShareRewardForm f, YearMonth month) {
        return Math.min(Math.max(0, f.shareRewardMonthlyCap()), dailySum(f) * daysOf(month));
    }

    /**
     * 估算结果：{@code days} 当月天数、{@code channelSum} = Σ(每次 × 每日上限) × 天数、{@code max} = 实际每账号每月最多可得
     * （总开关关着时为 0 —— 关着就一枚都不发）。弹层的数字与说明都取自这一个结果，不在别处重算。
     */
    public record Result(int days, long channelSum, long max) {
    }

    public static Result estimate(ShareRewardForm f, YearMonth month) {
        int days = daysOf(month);
        long sum = dailySum(f) * days;
        return new Result(days, sum, f.shareRewardEnabled() ? Math.min(Math.max(0, f.shareRewardMonthlyCap()), sum) : 0L);
    }

    /** 当前时刻所在的发放周期月（与 {@code ShareRewardService.periodOf} 同一处月界判定，WIB）。 */
    public static YearMonth currentMonth(Instant now) {
        return YearMonth.parse(com.tailtopia.share.service.ShareRewardService.periodOf(now));
    }

    /**
     * 是否要弹估算行：改了会改变估算的字段（四渠道金额 / 每日上限、月度上限），或把总开关从关打开（从不发到真发钱）。
     * 只是关开关不弹估算。
     */
    public static boolean amountsChanged(ShareRewardForm before, ShareRewardForm after) {
        return (!before.shareRewardEnabled() && after.shareRewardEnabled())
                || before.shareRewardMonthlyCap() != after.shareRewardMonthlyCap()
                || before.idCardShareReward() != after.idCardShareReward()
                || before.idCardShareDailyCap() != after.idCardShareDailyCap()
                || before.ageCardShareReward() != after.ageCardShareReward()
                || before.ageCardShareDailyCap() != after.ageCardShareDailyCap()
                || before.tailsonalityShareReward() != after.tailsonalityShareReward()
                || before.tailsonalityShareDailyCap() != after.tailsonalityShareDailyCap()
                || before.passportShareReward() != after.passportShareReward()
                || before.passportShareDailyCap() != after.passportShareDailyCap();
    }
}

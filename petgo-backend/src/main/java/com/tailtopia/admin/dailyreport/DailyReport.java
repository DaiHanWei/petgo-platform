package com.tailtopia.admin.dailyreport;

import java.time.LocalDate;

/**
 * 一份日报：统计日（昨天）与前一天两组指标，环比由两组现算。
 *
 * @param date     统计日（WIB 自然日）
 * @param current  统计日指标
 * @param previous 前一天指标（环比分母）
 */
public record DailyReport(LocalDate date, Metrics current, Metrics previous) {

    /**
     * 某一天的指标。金额单位 IDR（整数）。
     *
     * @param dau 日活；该日尚无逐日活跃记录（上线前 / 上线当天不完整）时为 null —— 不编数字
     */
    public record Metrics(
            long newUsers,
            Long dau,
            long newPosts,
            long comments,
            long likes,
            long paidOrders,
            long payingUsers,
            long paidAmount,
            long shopOrders,
            long gmv) {

        /** 评论活跃率 = 评论数 / 日活。日活缺失或为 0 → null（无法计算）。 */
        public Double commentRate() {
            return ratio(comments, dau);
        }

        /** like 活跃率 = like 数 / 日活。 */
        public Double likeRate() {
            return ratio(likes, dau);
        }

        private static Double ratio(long numerator, Long dau) {
            return dau == null || dau == 0 ? null : (double) numerator / dau;
        }
    }

    /**
     * 环比百分比：{@code (current - previous) / previous × 100}。
     * 分母为 0 或任一侧缺失 → null（无法计算，展示为「—」而不是 0% 或 ∞）。
     */
    public static Double changePct(Number current, Number previous) {
        if (current == null || previous == null || previous.doubleValue() == 0) {
            return null;
        }
        return (current.doubleValue() - previous.doubleValue()) / previous.doubleValue() * 100;
    }
}

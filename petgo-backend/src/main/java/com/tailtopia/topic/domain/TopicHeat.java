package com.tailtopia.topic.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 话题热度公式的<b>唯一一份</b>（V1.4.2 social-topic · Story 4.2 · AC7 · D-14 · PRD「热度算法」2026-11-02 口径）。
 *
 * <pre>
 * heat = 近 7 天新发帖数 × {@link #POST_WEIGHT}
 *      + 近 7 天按天浏览人数之和 × {@link #VIEW_WEIGHT}
 *      + 近 7 天发生的点赞数 × {@link #INTERACTION_WEIGHT}
 *      + 近 7 天发生的评论数 × {@link #INTERACTION_WEIGHT}
 * </pre>
 *
 * <p>🔴 {@code TopicHeatJob} 的集合 SQL 由这里的常量拼出（{@link #sqlWeight}），{@link #score} 供单测与对账 ——
 * 改权重只改这里，SQL 与纯函数同步变化，不得在别处另写一份公式。
 *
 * <p>结果落 {@code topics.heat_score NUMERIC(12,1)}，保留 1 位小数（非负数下 PG 四舍五入与 {@link RoundingMode#HALF_UP} 一致）。
 */
public final class TopicHeat {

    /** 近 7 天新发帖数权重。 */
    public static final BigDecimal POST_WEIGHT = BigDecimal.ONE;

    /** 近 7 天按天浏览人数之和权重（🔴 是 0.1，不是 1）。 */
    public static final BigDecimal VIEW_WEIGHT = new BigDecimal("0.1");

    /** 近 7 天点赞 / 评论数权重。 */
    public static final BigDecimal INTERACTION_WEIGHT = BigDecimal.ONE;

    /** 统计窗口天数（帖子 / 点赞 / 评论按 {@code now − 7 天}；按天浏览按含今天共 7 个 WIB 日）。 */
    public static final int WINDOW_DAYS = 7;

    private TopicHeat() {
    }

    /** 热度纯函数（与 {@code TopicHeatJob} SQL 同一公式）。 */
    public static BigDecimal score(long newPosts, long viewerDays, long likes, long comments) {
        return BigDecimal.valueOf(newPosts).multiply(POST_WEIGHT)
                .add(BigDecimal.valueOf(viewerDays).multiply(VIEW_WEIGHT))
                .add(BigDecimal.valueOf(likes).multiply(INTERACTION_WEIGHT))
                .add(BigDecimal.valueOf(comments).multiply(INTERACTION_WEIGHT))
                .setScale(1, RoundingMode.HALF_UP);
    }

    /** 权重拼进 SQL 的字面量（常量是代码内定值，不来自外部输入，无注入面）。 */
    public static String sqlWeight(BigDecimal weight) {
        return weight.toPlainString();
    }
}

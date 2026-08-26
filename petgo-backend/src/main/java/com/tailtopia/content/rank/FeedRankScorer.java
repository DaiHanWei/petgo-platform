package com.tailtopia.content.rank;

import java.time.Duration;
import java.time.Instant;

/**
 * 槽位内打分（V1.1.6 Story 16.2 · AC4）。
 *
 * <pre>
 *   内容分 = freshnessWeight × 新鲜度 + interactionWeight × 互动度
 *   新鲜度 = 1 / (1 + Δt / 24)                        Δt = 发布至今小时数
 *   互动度 = ln(1 + 赞 + commentWeight × 评) / ln(1 + P95)
 *   最终分 = 内容分 × 曝光衰减 × 荣誉加成 × 限流系数
 * </pre>
 *
 * <p>🛡 <b>不使用浏览量</b>（AC4）—— 已核实 {@code content_posts} 无该字段，
 * 埋点侧的浏览量不在业务库。想用它得先建一条数据链路，本 story 不做。
 */
final class FeedRankScorer {

    private FeedRankScorer() {
    }

    /** 新鲜度：24 小时衰减到 0.5，48 小时到 1/3。⚠️ 未来时间（时钟偏移）按 0 小时算，不给超过 1 的分。 */
    static double freshness(Instant createdAt, Instant now) {
        if (createdAt == null) {
            return 0d;
        }
        double hours = Duration.between(createdAt, now).toMillis() / 3_600_000d;
        if (hours < 0) {
            hours = 0;
        }
        return 1d / (1d + hours / 24d);
    }

    /**
     * 互动度（按 P95 归一化）。
     *
     * <p>🔴 {@code P95 <= 0} → 返回 0：{@code ln(1 + P95)} 是分母，P95=0 会除零。
     * 这不是防御性编程 —— 16.4 会把 P95 做成「近 30 天动态重算」的值，重算失败或冷启动时它就是 0。
     *
     * <p>⚠️ <b>刻意不上限截断</b>：互动量超过 P95 的内容互动度会 &gt; 1（爆款确实该更高）。
     * 若发版后发现爆款长期霸榜，调的是 {@code interactionWeight} 而不是在这里加 clamp ——
     * 加 clamp 会让「刚过 P95」和「10 倍 P95」变成同一个分，那是信息损失。
     */
    static double interaction(long likes, long comments, RankParams p) {
        if (p.interactionP95() <= 0) {
            return 0d;
        }
        double raw = likes + p.commentWeight() * comments;
        return Math.log1p(raw) / Math.log1p(p.interactionP95());
    }

    /** 内容分（不含四个乘法系数）。 */
    static double contentScore(RankCandidate c, Instant now, RankParams p) {
        return p.freshnessWeight() * freshness(c.createdAt(), now)
                + p.interactionWeight() * interaction(c.likes(), c.comments(), p);
    }

    /**
     * 最终分。
     *
     * @param decay    曝光衰减（16.1 供；未曝光 = 1.0）
     * @param honor    荣誉加成（带生效中装饰标签 = honorBoost，否则 1.0）
     * @param throttle 限流系数（Epic 17 供；本 story 恒 1.0）
     */
    static double finalScore(RankCandidate c, Instant now, RankParams p,
            double decay, double honor, double throttle) {
        return contentScore(c, now, p) * decay * honor * throttle;
    }
}

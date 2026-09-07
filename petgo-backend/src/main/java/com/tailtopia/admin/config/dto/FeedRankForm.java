package com.tailtopia.admin.config.dto;

/**
 * 推荐算法参数表单（V1.1.6 Story 16.4，FR-95）。
 *
 * <p>⚠️ <b>不含 P95</b>：它是定期重算的动态值，不是运营手填的常数。
 * 做成输入框只会让人以为"填个大点的数就能压住爆款"，而下一次重算就把它冲掉了。
 *
 * <p>{@code throttleFactor} 是 Story 17.1 · AC5 加的限流系数（平台级，默认 0.2）。
 */
public record FeedRankForm(
        double freshnessWeight,
        double interactionWeight,
        double commentWeight,
        double exposureDecay,
        /** 刷新抖动幅度 0–1（2026-09-01）：0=关闭，越大下拉刷新换得越狠。 */
        double shuffleStrength,
        double throttleFactor,
        int seenWindowDays,
        int windowSize,
        int attrFunQuota,
        int attrEduQuota,
        int attrLifeQuota,
        int speciesMainQuota,
        int speciesOtherQuota,
        int speciesGeneralQuota) {
}

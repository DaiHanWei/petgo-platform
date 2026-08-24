package com.tailtopia.admin.config.dto;

/**
 * 推荐算法参数表单（V1.1.6 Story 16.4，FR-95）。
 *
 * <p>⚠️ <b>不含 P95</b>：它是定期重算的动态值，不是运营手填的常数。
 * 做成输入框只会让人以为"填个大点的数就能压住爆款"，而下一次重算就把它冲掉了。
 */
public record FeedRankForm(
        double freshnessWeight,
        double interactionWeight,
        double commentWeight,
        double exposureDecay,
        int seenWindowDays,
        int windowSize,
        int attrFunQuota,
        int attrEduQuota,
        int attrLifeQuota,
        int speciesMainQuota,
        int speciesOtherQuota,
        int speciesGeneralQuota) {
}

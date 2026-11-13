package com.tailtopia.admin.config.dto;

/**
 * 推荐算法参数表单（V1.1.6 Story 16.4，FR-95）。
 *
 * <p>⚠️ <b>不含 P95</b>：它是定期重算的动态值，不是运营手填的常数。
 * 做成输入框只会让人以为"填个大点的数就能压住爆款"，而下一次重算就把它冲掉了。
 *
 * <p>{@code throttleFactor} 是 Story 17.1 · AC5 加的限流系数（平台级，默认 0.2）。
 *
 * <p>{@code authorLevelBoost} 是 V1.4.0 ① Story 3.4 加的作者等级加权（Lv15 及以上真实作者，默认 1.05，1 = 不加权）。
 *
 * <p>末尾 4 项是修复线（2026-11-12）加的：优质老帖候选池（回看天数 / 条数）、互动冷却天数、看过暂不出现小时数。
 */
public record FeedRankForm(
        double freshnessWeight,
        double interactionWeight,
        double commentWeight,
        double exposureDecay,
        /** 刷新抖动幅度 0–1（2026-09-01）：0=关闭，越大下拉刷新换得越狠。 */
        double shuffleStrength,
        double throttleFactor,
        double authorLevelBoost,
        int seenWindowDays,
        int windowSize,
        int attrFunQuota,
        int attrEduQuota,
        int attrLifeQuota,
        int speciesMainQuota,
        int speciesOtherQuota,
        int speciesGeneralQuota,
        int qualityPoolDays,
        int qualityPoolSize,
        int interactedCooldownDays,
        int seenHideHours) {

    /**
     * 修复线之前的 15 项构造：末尾 4 项取默认值（30 / 300 / 3 / 24）。
     * 只为不改动既有调用方（测试夹具）；后台保存走全参构造，4 项一律由表单提交。
     */
    public FeedRankForm(double freshnessWeight, double interactionWeight, double commentWeight,
            double exposureDecay, double shuffleStrength, double throttleFactor, double authorLevelBoost,
            int seenWindowDays, int windowSize, int attrFunQuota, int attrEduQuota, int attrLifeQuota,
            int speciesMainQuota, int speciesOtherQuota, int speciesGeneralQuota) {
        this(freshnessWeight, interactionWeight, commentWeight, exposureDecay, shuffleStrength, throttleFactor,
                authorLevelBoost, seenWindowDays, windowSize, attrFunQuota, attrEduQuota, attrLifeQuota,
                speciesMainQuota, speciesOtherQuota, speciesGeneralQuota, 30, 300, 3, 24);
    }
}

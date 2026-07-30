package com.tailtopia.content.moderation;

/**
 * 词库分类结果（内容审核 Story 1 · §5.4）。
 *
 * @param l1Blocked  命中 L1 黑名单且未被 L3 白名单豁免 → 硬拦截
 * @param l1Category 触发硬拦截的类别（DRUGS/GAMBLING/...），无则 null
 * @param l2Weight   L2 中风险加权（0–1，并入三方评分，非硬拦截）
 * @param l2Category L2 命中的最高类别，无则 null
 */
public record KeywordClassification(boolean l1Blocked, String l1Category, double l2Weight, String l2Category) {

    static final KeywordClassification NONE = new KeywordClassification(false, null, 0.0, null);
}

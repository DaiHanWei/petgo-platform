package com.tailtopia.shop.repurchase.dto;

import java.util.List;

/**
 * 档案推荐结果（Story 6.2，FR-107）。
 *
 * <p>🔴 <b>{@code degraded=true} 时前端要在尾部展示「补全档案，推荐更准」引导卡</b>（Story 6.5）——
 * 那是 L-9 存量用户回填体重的<b>唯一入口</b>。所以这个标记不是调试字段，是产品链路的一环。
 */
public record RecommendationView(
        /** 🔴 档案不完整 → 降级为按物种推荐。降级路径<b>不报错、不返回空</b>。 */
        boolean degraded,
        /** 缺了什么（WEIGHT / AGE / BOTH / NONE），供引导卡决定提示补什么。 */
        String missing,
        String petName,
        List<Item> items,
        /**
         * v1.4.2 shop Story 6.1 AC13（D-17，PRD A1 / A9 栏头）：档案摘要枚举，App 用 arb 词表拼本地化文案，后端不拼句子。
         * 🔒 未建档与<b>静默期</b>为 null（不能让客户端从摘要推断出静默态）。
         */
        ProfileSummary profileSummary) {

    /**
     * 档案摘要（取值只来自推荐理由用的同一份 {@code ProfileFacts}）。
     *
     * @param species       CAT / DOG / UNIVERSAL（{@code PetType.OTHER} → UNIVERSAL）
     * @param sizeClass     SMALL / MEDIUM / LARGE；体重未填 → null
     * @param ageStage      PUPPY / ADULT / SENIOR；生日缺失 → null
     * @param weightMissing 体重未填
     */
    public record ProfileSummary(String petName, String species, String sizeClass, String ageStage,
            boolean weightMissing) {
    }

    /**
     * 一条推荐。
     *
     * @param reason 🔴 <b>推荐理由文本</b>（如 {@code Untuk anjing dewasa 10–25 kg}）——
     *     不可解释的推荐在信任驱动的产品里是负资产
     * @param promo  v1.4.2 shop Story 2.2（FR-133.3「Lagi promo」，{@code PromoResolver}）；只加字段
     */
    public record Item(String productToken, String name, String brand, String mainImageKey,
            String mainImageUrl, long minPrice, String reason, boolean promo) {
    }

    /** 空结果（未建档 / 静默期）：{@code profileSummary = null}。 */
    public static RecommendationView empty(String petName, boolean degraded, String missing) {
        return new RecommendationView(degraded, missing, petName, List.of(), null);
    }
}

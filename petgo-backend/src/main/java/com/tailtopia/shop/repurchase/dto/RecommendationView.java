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
        List<Item> items) {

    /**
     * 一条推荐。
     *
     * @param reason 🔴 <b>推荐理由文本</b>（如 {@code Untuk anjing dewasa 10–25 kg}）——
     *     不可解释的推荐在信任驱动的产品里是负资产
     */
    public record Item(String productToken, String name, String brand, String mainImageKey,
            String mainImageUrl, long minPrice, String reason) {
    }

    public static RecommendationView empty(String petName, boolean degraded, String missing) {
        return new RecommendationView(degraded, missing, petName, List.of());
    }
}

package com.tailtopia.shop.domain;

/**
 * 每日建议喂量的一条体重区间记录（FR-94 ⑩）。
 *
 * <p>🔴 <b>这是 FR-109 粮量见底预估的唯一计算依据，必须结构化，绝不接受自由文本。</b>
 * 填成描述性文字会导致整条复购机制失效——而 FR-108 已移出本版本（C-11），复购引擎冗余归零，
 * FR-109 一旦不可用，本版本实际只剩 FR-107。
 *
 * <p>整表以 JSONB 数组存于 {@code shop_products.feeding_guide}，形如
 * {@code [{"weightMinKg":5,"weightMaxKg":10,"gramsPerDay":110}]}。
 *
 * <p>⚠️ 数据本身依赖 <b>DEP-6</b>（首批 SKU 的每日建议喂量，责任方 Rendy）。未到位时 FR-109
 * 按其自身规则「缺输入不触发、不做兜底猜测」静默降级——<b>该路径是一等路径，不是异常</b>。
 *
 * @param weightMinKg 体重区间下限（千克，含）
 * @param weightMaxKg 体重区间上限（千克，含）
 * @param gramsPerDay 该区间的每日建议喂量（克）
 */
public record FeedingGuideEntry(int weightMinKg, int weightMaxKg, int gramsPerDay) {
}

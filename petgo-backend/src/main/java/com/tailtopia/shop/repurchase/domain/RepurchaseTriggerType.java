package com.tailtopia.shop.repurchase.domain;

/**
 * 复购触发类型（Story 6.3）。
 *
 * <p>🔴 <b>保留三值是为了 1.2.0 FR-108 上线时能直接追加数据、不断历史序列</b>。
 * ⚠️ <b>本版本只会产生 {@link #FOOD_LOW}</b> —— FR-108（驱虫/疫苗周期提醒）已移出本版本（C-11）。
 *
 * <p>⚠️ AB-13B 看板必须写明「{@link #DEWORM} / {@link #VACCINE} 恒为 0 是<b>范围决策</b>，
 * 不是数据丢失」，否则运营会报「埋点坏了」。
 */
public enum RepurchaseTriggerType {
    /** 驱虫周期提醒。⚠️ 1.2.0 才产生，本版本恒为 0。 */
    DEWORM,
    /** 疫苗周期提醒。⚠️ 1.2.0 才产生，本版本恒为 0。 */
    VACCINE,
    /** 粮量见底（FR-109）。本版本唯一会产生的类型。 */
    FOOD_LOW
}

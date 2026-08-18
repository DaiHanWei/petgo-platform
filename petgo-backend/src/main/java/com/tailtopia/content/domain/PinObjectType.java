package com.tailtopia.content.domain;

/**
 * 可顶置对象的两种类型（FR-68），**同一坑位同一时段只能是其中一种**。
 */
public enum PinObjectType {
    /** (a) 顶置一篇已发布的**公开**内容；该内容同时仍保留在原有时间线位置（顶置是额外展示）。 */
    CONTENT,
    /** (b) 运营直接配的推广卡片，不对应任何真实帖子。 */
    PROMO
}

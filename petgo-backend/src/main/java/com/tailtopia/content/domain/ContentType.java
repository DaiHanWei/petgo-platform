package com.tailtopia.content.domain;

/**
 * 内容类型（FR-12，落库 varchar UPPER_SNAKE）。
 *
 * <ul>
 *   <li>{@link #DAILY}：日常分享。</li>
 *   <li>{@link #GROWTH_MOMENT}：成长日历快乐时刻——需绑定宠物档案，进档案时间线（2.4）。</li>
 *   <li>{@link #KNOWLEDGE}：专业科普。</li>
 * </ul>
 * 「全部」是浏览态语义，非可发布类型——发布时须落具体类型。
 */
public enum ContentType {
    DAILY,
    GROWTH_MOMENT,
    KNOWLEDGE
}

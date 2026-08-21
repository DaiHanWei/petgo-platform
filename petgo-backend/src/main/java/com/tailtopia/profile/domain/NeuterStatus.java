package com.tailtopia.profile.domain;

/**
 * 绝育状态（Story 6.1）。FR-107 的可选过滤维度。
 *
 * <p>🔴 <b>{@link #UNKNOWN} 与「字段为 null」不是一回事</b>：
 * null = 用户没填过（跳过了）；UNKNOWN = 用户明确表示「不知道」（领养的成年宠常见）。
 * 混为一谈会让「有多少人还没填」这个引导决策依据失真。
 */
public enum NeuterStatus {
    NEUTERED,
    INTACT,
    UNKNOWN
}

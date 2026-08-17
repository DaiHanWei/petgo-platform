package com.tailtopia.shop.domain;

/**
 * 适用年龄段（FR-94 ⑧，选填）。FR-107 按档案年龄匹配：幼年 &lt;1 岁 / 成年 1–7 岁 / 老年 &gt;7 岁
 * （犬猫阈值可后台配置，配置属 1.3）。
 *
 * <p>落库 varchar + CHECK（可空），UPPER_SNAKE。只在末尾追加。
 */
public enum AgeStage {
    PUPPY,
    ADULT,
    SENIOR,
    UNIVERSAL
}

package com.tailtopia.shop.domain;

/**
 * 适用体型（FR-94 ⑦，选填）。FR-107 档案推荐按档案体重匹配的依据。
 *
 * <p>落库 varchar + CHECK（可空），UPPER_SNAKE。只在末尾追加。
 */
public enum BodySize {
    SMALL,
    MEDIUM,
    LARGE,
    UNIVERSAL
}

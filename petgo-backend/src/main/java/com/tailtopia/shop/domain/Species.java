package com.tailtopia.shop.domain;

/**
 * 适用物种（FR-94 ⑥）。FR-107 档案推荐的第一步<b>硬过滤</b>依据。
 *
 * <p>落库 varchar + CHECK，UPPER_SNAKE。只在末尾追加。
 */
public enum Species {
    DOG,
    CAT,
    /** 通用：不参与物种硬过滤 */
    UNIVERSAL
}

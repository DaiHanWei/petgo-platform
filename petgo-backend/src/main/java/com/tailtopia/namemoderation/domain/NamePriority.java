package com.tailtopia.namemoderation.domain;

/**
 * 名称审核人工队列优先级（内容审核 story 4，落库 UPPER_SNAKE）。
 * {@code HIGH} = 三方评分 ≥0.8 或 L1 硬命中；其余入队为 {@code NORMAL}（含 fail-closed 降级）。
 */
public enum NamePriority {
    NORMAL,
    HIGH
}

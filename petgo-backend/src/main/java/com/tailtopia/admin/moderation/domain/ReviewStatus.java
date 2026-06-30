package com.tailtopia.admin.moderation.domain;

/**
 * 人工审核队列项状态（Story 4.3，落库 varchar UPPER_SNAKE）。
 * <ul>
 *   <li>{@link #PENDING}：挂起待运营处置。</li>
 *   <li>{@link #APPROVED}：运营通过（内容转 PUBLISHED）。</li>
 *   <li>{@link #REJECTED}：运营拒绝（内容丢弃）。</li>
 *   <li>{@link #TIMED_OUT}：超 3 天未处置自动丢弃。</li>
 * </ul>
 */
public enum ReviewStatus {
    PENDING,
    APPROVED,
    REJECTED,
    TIMED_OUT
}

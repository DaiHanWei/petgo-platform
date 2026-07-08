package com.tailtopia.avatarmoderation.domain;

/**
 * 头像审核结论（内容审核 story 5，§4.1，落库 UPPER_SNAKE）。{@code status} 描述流水线位置，
 * {@code verdict} 描述最终结论；QUEUED（评分中）时为 null。
 *
 * <ul>
 *   <li>{@link #PASS} 自动通过（低风险）或运营判过。</li>
 *   <li>{@link #PENDING_REVIEW} 入人工队列待判（中/高风险或图像高置信违规）。</li>
 *   <li>{@link #VIOLATION} 运营判违规 → 已重置默认头像 + 推送（终态）。</li>
 *   <li>{@link #STALE_DISCARDED} 陈旧作废（出结果/处置时头像已改新值，或被新提交取代）→ 静默丢弃（D-CM3）。</li>
 *   <li>{@link #DEGRADED_QUEUED} 三方降级 fail-closed 入队（绝不自动放行，D-CM5）。</li>
 * </ul>
 */
public enum AvatarReviewVerdict {
    PASS,
    PENDING_REVIEW,
    VIOLATION,
    STALE_DISCARDED,
    DEGRADED_QUEUED
}

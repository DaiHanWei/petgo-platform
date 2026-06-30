package com.tailtopia.consult.dto;

/**
 * 单兽医评分质量摘要（Story 6.1，AB-6A）。口径以**会话 created_at**为单一时间窗，三项基于同一会话集：
 * <ul>
 *   <li>{@code ratedCount} = 窗内 CLOSED 会话中存在评分行的数量（含 UNRATED 关闭后用户补评的）。</li>
 *   <li>{@code unratedCount} = 窗内 CLOSED 会话中无评分行的数量。</li>
 *   <li>{@code average} = 上述已评会话评分 stars 的算术平均（保留一位小数；无评分为 0.0）。</li>
 * </ul>
 * INTERRUPTED（封禁中断）会话从不进入评分流程，不计入分母（归 Epic 5 工单域）。
 */
public record VetQualitySummary(int ratedCount, int unratedCount, double average) {

    public static final VetQualitySummary EMPTY = new VetQualitySummary(0, 0, 0.0);
}

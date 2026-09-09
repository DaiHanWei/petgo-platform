package com.tailtopia.admin.dashboard.domain;

/**
 * 看板指标口径（V1.3.0 Story 3.2，D-29）：{@code ALL} = 含种子（参考 SQL 原口径）；
 * {@code REAL} = 真实用户（帖子作者为真实用户，且点赞 / 评论发起者也为真实用户）。
 * 只有 {@link DashboardMetric#dualScope()} 为 true 的 9 项帖子类指标才有 REAL 序列。
 */
public enum MetricScope {
    ALL,
    REAL
}

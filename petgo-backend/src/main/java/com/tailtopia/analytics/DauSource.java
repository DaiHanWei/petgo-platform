package com.tailtopia.analytics;

/**
 * 日活（S1）取数来源（analytics-pipeline Story 4.2 AC6，FR-24）：属性 {@code analytics.dau-source}，默认 {@link #GA4}。
 *
 * <p>观察期内 S1 取 GA4（{@code ga4_daily_snapshot.active_users_all}）；对账通过、由 Dai 决定后改为 {@link #SELF}
 * （{@code agg_daily_activity.dau_person}），切换日期写进口径条目 S1 的变更记录。Story 4.2 只提供开关，读它的是 Story 4.7。
 */
public enum DauSource {
    /** GA4 全版本日活（按设备、含游客）。 */
    GA4,
    /** 自建日活（含游客，按人，D-10）。 */
    SELF
}

package com.tailtopia.admin.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 看板图表的一条序列（V1.3.0 Story 3.4 AC2）：与 {@link ChartData#labels()} 逐日对齐；
 * <b>未物化的日子为 {@code null}（断点），已物化的 0 照传 0</b>（D-30：null 与 0 必须区分）。
 *
 * @param key   {@code DashboardMetric.key()}
 * @param scope {@code ALL} / {@code REAL}
 */
public record ChartSeries(String key, String scope, List<BigDecimal> values) {
}

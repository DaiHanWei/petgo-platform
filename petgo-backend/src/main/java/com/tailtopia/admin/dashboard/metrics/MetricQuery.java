package com.tailtopia.admin.dashboard.metrics;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单个看板指标的取数口径（V1.3.0 Story 3.2 AC3）：每指标一个 {@code @Component} 实现，全部原生 SQL、只读业务表、
 * <b>不写</b> {@code ops_daily_metrics}（AC5，写入归 3-3）。
 * 日期参数是 <b>WIB 自然日</b>；SQL 切日一律 {@code (ts AT TIME ZONE 'Asia/Jakarta')::date}（{@link MetricSql#day}）。
 */
public interface MetricQuery {

    DashboardMetric key();

    /**
     * 计算 {@code reportDate}（WIB 自然日，截止该日 24:00）该口径的值。
     * 非双口径指标传 {@link MetricScope#REAL} 抛 {@link IllegalArgumentException}（统一：只接受 ALL）。
     * 平均类指标（#12 / #13）空集返回 {@code null}（3-3 存 0 并记「无样本」）；其余非 null。
     */
    BigDecimal compute(LocalDate reportDate, MetricScope scope);
}

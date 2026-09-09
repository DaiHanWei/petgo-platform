package com.tailtopia.admin.dashboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 看板日粒度预聚合长表 {@code ops_daily_metrics}（V1.3.0 Story 3.2 AC1，AD-2）：一行 = (WIB 自然日, 指标, 口径)。
 * 历史行不可变（口径截止该日 24:00）；写入归 Story 3.3 跑批，本实体不含业务方法。
 * {@code value} 用 {@code NUMERIC(18,4)} ↔ {@link BigDecimal}（平均分有小数）。
 */
@Entity
@Table(name = "ops_daily_metrics")
public class OpsDailyMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** WIB 自然日。 */
    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    /** {@link DashboardMetric#key()}（snake_case）。 */
    @Column(name = "metric_key", nullable = false, length = 48)
    private String metricKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 8)
    private MetricScope scope;

    @Column(name = "value", nullable = false, precision = 18, scale = 4)
    private BigDecimal value;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected OpsDailyMetric() {
    }

    public static OpsDailyMetric of(LocalDate reportDate, DashboardMetric metric, MetricScope scope, BigDecimal value,
            Instant computedAt) {
        OpsDailyMetric m = new OpsDailyMetric();
        m.reportDate = reportDate;
        m.metricKey = metric.key();
        m.scope = scope;
        m.value = value;
        m.computedAt = computedAt;
        return m;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public DashboardMetric metric() {
        return DashboardMetric.fromKey(metricKey);
    }

    public MetricScope getScope() {
        return scope;
    }

    public BigDecimal getValue() {
        return value;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}

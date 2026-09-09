package com.tailtopia.admin.dashboard.metrics;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 指标查询基类（V1.3.0 Story 3.2）：口径校验 + 单值查询。子类只提供 {@link #sql(MetricScope)}。
 * 只依赖 JDBC，不注入业务 Repository（admin 新模块不直查业务 Repository；原生 SQL 是 AD-2 明示的例外）。
 */
public abstract class AbstractMetricQuery implements MetricQuery {

    private final DashboardMetric key;
    private final NamedParameterJdbcTemplate jdbc;

    protected AbstractMetricQuery(DashboardMetric key, NamedParameterJdbcTemplate jdbc) {
        this.key = Objects.requireNonNull(key);
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public final DashboardMetric key() {
        return key;
    }

    /** 该口径的完整 SQL（命名参数 {@code :d} = WIB 自然日）；公开是为了 L0 静态断言（切日 / 真实用户片段 / 无散写）。 */
    public abstract String sql(MetricScope scope);

    @Override
    public BigDecimal compute(LocalDate reportDate, MetricScope scope) {
        Objects.requireNonNull(reportDate, "reportDate");
        requireScope(scope);
        // pgjdbc 原生支持 LocalDate → DATE 绑定，不绕 JVM 默认时区（java.sql.Date.valueOf 会）
        return jdbc.queryForObject(sql(scope), new MapSqlParameterSource(MetricSql.PARAM_DAY, reportDate), BigDecimal.class);
    }

    /** 非双口径指标只接受 ALL（AC3 统一约定）。 */
    protected final void requireScope(MetricScope scope) {
        Objects.requireNonNull(scope, "scope");
        if (scope == MetricScope.REAL && !key.dualScope()) {
            throw new IllegalArgumentException("metric " + key.key() + " has no REAL scope (only ALL)");
        }
    }
}

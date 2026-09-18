package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #2 累计注册用户数（V1.3.0 Story 3.2）：截至当日 24:00 累计真实用户数（原「总安装用户数」，D-16 改名）。非双口径。
 */
@Component
public class CumulativeUsersMetricQuery extends AbstractMetricQuery {

    public CumulativeUsersMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.CUMULATIVE_USERS, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "SELECT count(*) FROM users u WHERE " + day("u.created_at") + " <= :d AND " + SyntheticAccountSql.EXCLUDE_WHERE;
    }
}

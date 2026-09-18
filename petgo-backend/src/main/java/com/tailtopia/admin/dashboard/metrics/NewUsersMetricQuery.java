package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #1 新增用户数（V1.3.0 Story 3.2）：当日注册的真实用户数（{@code users.created_at} 落在 WIB 当日，且 3-1 真实用户口径）。非双口径。
 */
@Component
public class NewUsersMetricQuery extends AbstractMetricQuery {

    public NewUsersMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.NEW_USERS, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "SELECT count(*) FROM users u WHERE " + day("u.created_at") + " = :d AND " + SyntheticAccountSql.EXCLUDE_WHERE;
    }
}

package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #6 累计建档用户数（V1.3.0 Story 3.2）：截至当日 24:00 至少拥有一个档案的去重用户数。非双口径。
 */
@Component
public class CumulativePetOwnersMetricQuery extends AbstractMetricQuery {

    public CumulativePetOwnersMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.CUMULATIVE_PET_OWNERS, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "SELECT count(DISTINCT pp.owner_id) FROM pet_profiles pp WHERE " + day("pp.created_at") + " <= :d";
    }
}

package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #5 新增建档用户数（V1.3.0 Story 3.2）：当日<b>首次</b>建档的去重用户数（D-28 按人去重；参考 SQL 按档案计的写法作废）。非双口径。
 */
@Component
public class NewPetOwnersMetricQuery extends AbstractMetricQuery {

    public NewPetOwnersMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.NEW_PET_OWNERS, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "SELECT count(*) FROM (SELECT pp.owner_id, min(pp.created_at) AS first_at FROM pet_profiles pp GROUP BY pp.owner_id) x"
                + " WHERE " + day("x.first_at") + " = :d";
    }
}

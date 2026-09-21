package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #10 总互动得分（V1.3.0 Story 3.2）：当日新产生的 赞×1 + 有效评论×5（双口径）。
 */
@Component
public class EngagementScoreMetricQuery extends AbstractMetricQuery {

    public EngagementScoreMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.ENGAGEMENT_SCORE, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "WITH " + MetricSql.interactionsCte(scope)
                + " SELECT coalesce(sum(" + MetricSql.SCORE_CASE + "), 0) FROM interactions i WHERE " + day("i.created_at") + " = :d";
    }
}

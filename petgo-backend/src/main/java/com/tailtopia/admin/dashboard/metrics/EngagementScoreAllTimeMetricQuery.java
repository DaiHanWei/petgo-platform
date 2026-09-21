package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #14 总互动得分（全部历史）（V1.3.0 Story 3.2）：截至当日 24:00 全部历史 赞×1 + 有效评论×5 累计（双口径）。
 */
@Component
public class EngagementScoreAllTimeMetricQuery extends AbstractMetricQuery {

    public EngagementScoreAllTimeMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.ENGAGEMENT_SCORE_ALL_TIME, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "WITH " + MetricSql.interactionsCte(scope)
                + " SELECT coalesce(sum(" + MetricSql.SCORE_CASE + "), 0) FROM interactions i WHERE " + day("i.created_at") + " <= :d";
    }
}

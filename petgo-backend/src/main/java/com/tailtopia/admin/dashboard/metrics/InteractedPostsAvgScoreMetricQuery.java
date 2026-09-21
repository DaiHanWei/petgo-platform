package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #13 当日互动帖子平均得分（V1.3.0 Story 3.2）：当日有互动的帖子按各自<b>当日新增</b>得分取平均（双口径）。空集返回 null。
 */
@Component
public class InteractedPostsAvgScoreMetricQuery extends AbstractMetricQuery {

    public InteractedPostsAvgScoreMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.INTERACTED_POSTS_AVG_SCORE, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "WITH " + MetricSql.interactionsCte(scope)
                + ", per_post AS (SELECT i.post_id, sum(" + MetricSql.SCORE_CASE + ") AS score FROM interactions i WHERE "
                + day("i.created_at") + " = :d GROUP BY i.post_id)"
                + " SELECT avg(x.score) FROM per_post x";
    }
}

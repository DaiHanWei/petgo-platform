package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #8 有互动的帖子数（V1.3.0 Story 3.2）：当日收到 ≥1 赞或有效评论的去重帖子数（不限发布日）；REAL = 互动者与帖子作者都是真实用户（双口径）。
 */
@Component
public class InteractedPostsMetricQuery extends AbstractMetricQuery {

    public InteractedPostsMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.INTERACTED_POSTS, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "WITH " + MetricSql.interactionsCte(scope)
                + " SELECT count(DISTINCT i.post_id) FROM interactions i WHERE " + day("i.created_at") + " = :d";
    }
}

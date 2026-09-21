package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #11 今日帖子总得分（V1.3.0 Story 3.2）：当日发布的可见帖各自截至当日 24:00 累计得分之和（双口径；截止点固定，补跑可复现）。
 */
@Component
public class NewPostsScoreMetricQuery extends AbstractMetricQuery {

    public NewPostsScoreMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.NEW_POSTS_SCORE, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "WITH " + MetricSql.interactionsCte(scope)
                + " SELECT coalesce(sum(s.score), 0) FROM content_posts p" + MetricSql.realAuthorJoin(scope)
                + MetricSql.postScoreLateral()
                + " WHERE " + day("p.created_at") + " = :d AND " + MetricSql.VISIBLE_POST_WHERE;
    }
}

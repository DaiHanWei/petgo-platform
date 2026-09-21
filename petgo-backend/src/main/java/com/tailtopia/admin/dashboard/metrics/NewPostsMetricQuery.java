package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #4 新增帖子数（V1.3.0 Story 3.2）：当日发布的可见帖子数；REAL 口径加「作者为真实用户」（双口径）。
 */
@Component
public class NewPostsMetricQuery extends AbstractMetricQuery {

    public NewPostsMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.NEW_POSTS, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "SELECT count(*) FROM content_posts p" + MetricSql.realAuthorJoin(scope)
                + " WHERE " + day("p.created_at") + " = :d AND " + MetricSql.VISIBLE_POST_WHERE;
    }
}

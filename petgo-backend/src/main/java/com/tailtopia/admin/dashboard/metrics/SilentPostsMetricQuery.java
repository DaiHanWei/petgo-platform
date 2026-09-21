package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #9 今日沉默帖子数（V1.3.0 Story 3.2）：当日发布的可见帖中当日 0 赞 0 有效评论的帖子数；REAL 口径作者与互动者都按真实用户过滤（双口径）。
 */
@Component
public class SilentPostsMetricQuery extends AbstractMetricQuery {

    public SilentPostsMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.SILENT_POSTS, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "WITH " + MetricSql.interactionsCte(scope)
                + " SELECT count(*) FROM content_posts p" + MetricSql.realAuthorJoin(scope)
                + " WHERE " + day("p.created_at") + " = :d AND " + MetricSql.VISIBLE_POST_WHERE
                + " AND NOT EXISTS (SELECT 1 FROM interactions i WHERE i.post_id = p.id AND " + day("i.created_at") + " = :d)";
    }
}

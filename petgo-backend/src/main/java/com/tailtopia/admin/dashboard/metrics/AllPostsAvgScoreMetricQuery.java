package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #12 全部帖子全量平均分（V1.3.0 Story 3.2）：截至当日已发布的全部可见帖各自累计得分的平均（被存量摊薄，看趋势；双口径）。空集返回 null。
 */
@Component
public class AllPostsAvgScoreMetricQuery extends AbstractMetricQuery {

    public AllPostsAvgScoreMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.ALL_POSTS_AVG_SCORE, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "WITH " + MetricSql.interactionsCte(scope)
                + " SELECT avg(s.score) FROM content_posts p" + MetricSql.realAuthorJoin(scope)
                + MetricSql.postScoreLateral()
                + " WHERE " + day("p.created_at") + " <= :d AND " + MetricSql.VISIBLE_POST_WHERE;
    }
}

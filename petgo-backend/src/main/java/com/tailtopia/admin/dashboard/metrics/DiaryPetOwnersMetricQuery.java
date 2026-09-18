package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #7 当日发 diary 的建档用户数（V1.3.0 Story 3.2）：当日发布 {@code GROWTH_MOMENT} 可见帖且本人截至当日 24:00 已建档的去重用户数（档案加时间上界，补跑可复现）。非双口径。
 */
@Component
public class DiaryPetOwnersMetricQuery extends AbstractMetricQuery {

    public DiaryPetOwnersMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.DIARY_PET_OWNERS, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "SELECT count(DISTINCT p.author_id) FROM content_posts p WHERE " + day("p.created_at") + " = :d AND "
                + MetricSql.VISIBLE_POST_WHERE + " AND p.type = 'GROWTH_MOMENT'"
                + " AND EXISTS (SELECT 1 FROM pet_profiles pp WHERE pp.owner_id = p.author_id AND " + day("pp.created_at") + " <= :d)";
    }
}

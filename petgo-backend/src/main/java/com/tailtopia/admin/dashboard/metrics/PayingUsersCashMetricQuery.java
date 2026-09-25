package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #15 付费人数·现金到账（V1.3.0 Story 3.2）：当日 {@code payment_intents.status = PAID}（到账时刻取 {@code updated_at}）的去重真实用户数，覆盖 QRIS 全部用途。非双口径。
 */
@Component
public class PayingUsersCashMetricQuery extends AbstractMetricQuery {

    public PayingUsersCashMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.PAYING_USERS_CASH, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "SELECT count(DISTINCT pi.user_id) " + "FROM payment_intents pi JOIN users u ON u.id = pi.user_id AND " + SyntheticAccountSql.EXCLUDE_WHERE
                + " WHERE pi.status = 'PAID' AND " + day("pi.updated_at") + " = :d";
    }
}

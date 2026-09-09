package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #16 付费次数·现金到账（V1.3.0 Story 3.2）：同 #15 的笔数，不去重。非双口径。
 */
@Component
public class PaymentsCashMetricQuery extends AbstractMetricQuery {

    public PaymentsCashMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.PAYMENTS_CASH, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "SELECT count(*) " + "FROM payment_intents pi JOIN users u ON u.id = pi.user_id AND " + SyntheticAccountSql.EXCLUDE_WHERE
                + " WHERE pi.status = 'PAID' AND " + day("pi.updated_at") + " = :d";
    }
}

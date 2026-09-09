package com.tailtopia.admin.dashboard.metrics;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #18 付费次数·含 PawCoin 消费（V1.3.0 Story 3.2）：同 #17 的笔数，不去重；已退款订单当日仍计（退款不追溯改写付款事实）。非双口径。
 */
@Component
public class PaymentsInclPawcoinMetricQuery extends AbstractMetricQuery {

    public PaymentsInclPawcoinMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.PAYMENTS_INCL_PAWCOIN, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "WITH " + PayingUsersInclPawcoinMetricQuery.spendEventsCte() + " SELECT count(*) FROM spend_events se";
    }
}

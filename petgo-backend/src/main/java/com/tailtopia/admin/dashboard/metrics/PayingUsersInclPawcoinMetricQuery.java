package com.tailtopia.admin.dashboard.metrics;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.domain.DashboardMetric;
import com.tailtopia.admin.dashboard.domain.MetricScope;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * #17 付费人数·含 PawCoin 消费（V1.3.0 Story 3.2）：当日 问诊 {@code paid_at} ∪ AI 解锁（COMPLETED）{@code paid_at}
 * ∪ 身份证高清成交（PawCoin 行 {@code purchased_at} ∪ ID_HD 支付单 PAID 到账）∪ PawCoin 充值到账 的去重真实用户数；
 * 已退款订单当日仍计（退款不追溯改写付款事实）。非双口径。
 */
@Component
public class PayingUsersInclPawcoinMetricQuery extends AbstractMetricQuery {

    public PayingUsersInclPawcoinMetricQuery(NamedParameterJdbcTemplate jdbc) {
        super(DashboardMetric.PAYING_USERS_INCL_PAWCOIN, jdbc);
    }

    @Override
    public String sql(MetricScope scope) {
        return "WITH " + spendEventsCte() + " SELECT count(DISTINCT se.user_id) FROM spend_events se";
    }

    /**
     * 消费成交事件 CTE（参考 SQL {@code spend_events}，四段 UNION ALL，各段 JOIN 真实用户）：
     * 问诊 paid_at ∪ AI 解锁 COMPLETED paid_at ∪ 身份证高清成交（PawCoin 行 purchased_at ∪ ID_HD 支付单 PAID 到账）∪ PawCoin 充值到账
     * （payment_intents PAID + PAWCOIN_TOPUP）。不加 {@code co.status NOT IN ('REFUNDING','REFUNDED')}（已退款当日仍计）。#18 复用。
     */
    static String spendEventsCte() {
        return "spend_events AS ("
                + "SELECT co.user_id FROM consult_orders co JOIN users u ON u.id = co.user_id AND " + SyntheticAccountSql.EXCLUDE_WHERE
                + " WHERE co.paid_at IS NOT NULL AND " + day("co.paid_at") + " = :d"
                + " UNION ALL SELECT ao.user_id FROM ai_consult_orders ao JOIN users u ON u.id = ao.user_id AND "
                + SyntheticAccountSql.EXCLUDE_WHERE
                + " WHERE ao.status = 'COMPLETED' AND ao.paid_at IS NOT NULL AND " + day("ao.paid_at") + " = :d"
                // 身份证高清：V92 起 id_card_hd_purchases 是「支付尝试 / 收据」表（QRIS 下单即插行，此时 intent 仍 PENDING），
                // 只有 PawCoin 行 = 当场成交；QRIS 成交以 ID_HD 支付单 PAID（到账日）为准，避免把放弃 / 超时的二维码算成付费
                + " UNION ALL SELECT hd.user_id FROM id_card_hd_purchases hd JOIN users u ON u.id = hd.user_id AND "
                + SyntheticAccountSql.EXCLUDE_WHERE
                + " WHERE hd.pay_channel = 'PAWCOIN' AND " + day("hd.purchased_at") + " = :d"
                + " UNION ALL SELECT pi.user_id FROM payment_intents pi JOIN users u ON u.id = pi.user_id AND "
                + SyntheticAccountSql.EXCLUDE_WHERE
                + " WHERE pi.status = 'PAID' AND pi.purpose = 'ID_HD' AND " + day("pi.updated_at") + " = :d"
                + " UNION ALL SELECT pi.user_id FROM payment_intents pi JOIN users u ON u.id = pi.user_id AND "
                + SyntheticAccountSql.EXCLUDE_WHERE
                + " WHERE pi.status = 'PAID' AND pi.purpose = 'PAWCOIN_TOPUP' AND " + day("pi.updated_at") + " = :d)";
    }
}

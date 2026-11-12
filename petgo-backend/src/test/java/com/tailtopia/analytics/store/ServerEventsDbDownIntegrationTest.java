package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.shared.analytics.SelfHostedAnalyticsClient;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：埋点库连不上（管道停用）时完成一笔 Toko 付款 —— 业务成功、无异常，服务端事件丢弃计数
 * （analytics-pipeline Story 1.9 AC3）。埋点库指向不存在的库，做法同 {@code AnalyticsDbDownIntegrationTest}。
 */
@TestPropertySource(properties =
        "analytics.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/petgo_analytics_absent")
class ServerEventsDbDownIntegrationTest extends ServerEventsTestSupport {

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    private SelfHostedAnalyticsClient analytics;

    @Test
    void paymentSucceedsWhilePipelineDisabled() {
        assertThat(state.isEnabled()).isFalse();
        long droppedBefore = analytics.droppedCount();

        PaidOrder paid = payOneOrder();

        assertThat(orders.findByPublicToken(paid.orderToken()).orElseThrow().getStatus())
                .as("埋点管道停用不影响付款到账")
                .isNotEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        assertThat(jdbc.queryForObject("SELECT paid_at IS NOT NULL FROM shop_orders WHERE public_token = ?",
                Boolean.class, paid.orderToken())).isTrue();
        // 至少 shop_payment_intent_created + shop_payment_paid 两条被丢弃计数
        assertThat(analytics.droppedCount() - droppedBefore).isGreaterThanOrEqualTo(2);
    }
}

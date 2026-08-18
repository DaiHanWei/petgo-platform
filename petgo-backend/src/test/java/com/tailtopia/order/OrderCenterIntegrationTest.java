package com.tailtopia.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultRequestRepository;
import com.tailtopia.order.dto.OrderPage;
import com.tailtopia.order.dto.OrderSummaryView;
import com.tailtopia.order.service.OrderCenterService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.triage.domain.AiConsultOrder;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（需 Docker postgres+redis）。Story 5.1 订单聚合接口。
 *
 * <p>核心：跨 3 源（兽医/AI/充值）按 created_at 倒序合并 + 游标翻页无重漏 + 类型筛选 + PawCoin 汇总；
 * 兽医 REFUNDING→INFO（非红）；consult_requests（待接单）不入订单中心（A-5）；仅本人。
 */
class OrderCenterIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private OrderCenterService orderCenter;
    @Autowired
    private ConsultOrderRepository consultOrders;
    @Autowired
    private AiConsultOrderRepository aiOrders;
    @Autowired
    private PaymentIntentRepository intents;
    @Autowired
    private ConsultRequestRepository requests;
    @Autowired
    private JdbcTemplate jdbc;

    private long n() {
        return SEQ.incrementAndGet();
    }

    private ConsultOrder seedVet(long userId, long amount) {
        ConsultOrder o = ConsultOrder.inProgress("ord-v-" + n(), userId, 1L, 1L, amount,
                PayChannel.QRIS, null, 30000, 60, 50000, Instant.now());
        o.markCompleted(Instant.now());
        return consultOrders.save(o);
    }

    private AiConsultOrder seedAi(long userId, long amount) {
        return aiOrders.save(AiConsultOrder.completedPawCoin("ord-a-" + n(), userId, 1L, amount));
    }

    private PaymentIntent seedTopup(long userId, long amount) {
        PaymentIntent i = PaymentIntent.create(userId, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS,
                amount, "IDR", "ord-t-" + n());
        i.markPaid(Map.of());
        return intents.save(i);
    }

    /** 拨 created_at 到确定时刻，令跨源排序可断言。 */
    private void setCreatedAt(String table, String tokenCol, String token, Instant at) {
        jdbc.update("UPDATE " + table + " SET created_at = ? WHERE " + tokenCol + " = ?",
                java.sql.Timestamp.from(at), token);
    }

    @Test
    void aggregatesThreeSources_descByCreatedAt_withColorsAndBalance() {
        long userId = newUser().getId();
        Instant base = Instant.now();
        ConsultOrder vet = seedVet(userId, 50000);
        AiConsultOrder ai = seedAi(userId, 5000);
        PaymentIntent top = seedTopup(userId, 25000);
        setCreatedAt("consult_orders", "order_token", vet.getOrderToken(), base.minus(30, ChronoUnit.SECONDS));
        setCreatedAt("ai_consult_orders", "order_token", ai.getOrderToken(), base.minus(20, ChronoUnit.SECONDS));
        setCreatedAt("payment_intents", "public_token", top.getPublicToken(), base.minus(10, ChronoUnit.SECONDS));

        OrderPage page = orderCenter.listOrders(userId, null, null, 20);

        assertThat(page.items()).hasSize(3);
        // 倒序：topup(-10s) > ai(-20s) > vet(-30s)
        assertThat(page.items()).extracting(OrderSummaryView::orderType)
                .containsExactly("PAWCOIN_TOPUP", "AI_UNLOCK", "VET_CONSULT");
        assertThat(page.items()).allSatisfy(v -> assertThat(v.statusColor()).isEqualTo("SUCCESS"));
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void typeFilter_onlyThatSource() {
        long userId = newUser().getId();
        seedVet(userId, 50000);
        seedAi(userId, 5000);
        seedTopup(userId, 25000);

        OrderPage vetOnly = orderCenter.listOrders(userId, "VET_CONSULT", null, 20);
        assertThat(vetOnly.items()).hasSize(1);
        assertThat(vetOnly.items().get(0).orderType()).isEqualTo("VET_CONSULT");
    }

    @Test
    void refundingOrder_mapsToInfoColor_notError() {
        long userId = newUser().getId();
        ConsultOrder vet = seedVet(userId, 50000);
        jdbc.update("UPDATE consult_orders SET status = 'REFUNDING' WHERE id = ?", vet.getId());

        OrderPage page = orderCenter.listOrders(userId, "VET_CONSULT", null, 20);
        assertThat(page.items().get(0).statusCode()).isEqualTo("REFUNDING");
        assertThat(page.items().get(0).statusColor()).isEqualTo("INFO"); // 退款中蓝非红
    }

    @Test
    void consultRequests_notInOrderCenter() {
        long userId = newUser().getId();
        seedVet(userId, 50000); // 1 已付订单
        // 待接单请求（A-5：取消即删、不进订单中心）
        requests.save(ConsultRequest.queue(userId, 1L, "req-" + n(), Instant.now().plusSeconds(60)));

        OrderPage page = orderCenter.listOrders(userId, null, null, 20);
        assertThat(page.items()).hasSize(1); // 仅 1 已付订单，待接单请求不入
    }

    @Test
    void cursorPaging_noOverlapNoGap() {
        long userId = newUser().getId();
        Instant base = Instant.now();
        for (int i = 0; i < 3; i++) {
            ConsultOrder o = seedVet(userId, 10000 + i);
            setCreatedAt("consult_orders", "order_token", o.getOrderToken(),
                    base.minus(10L * (i + 1), ChronoUnit.SECONDS));
        }

        OrderPage p1 = orderCenter.listOrders(userId, null, null, 2);
        assertThat(p1.items()).hasSize(2);
        assertThat(p1.hasMore()).isTrue();
        assertThat(p1.nextCursor()).isNotNull();

        OrderPage p2 = orderCenter.listOrders(userId, null, p1.nextCursor(), 2);
        assertThat(p2.items()).hasSize(1);
        assertThat(p2.hasMore()).isFalse();
        // 无重叠：p2 的 token 不在 p1
        var p1Tokens = p1.items().stream().map(OrderSummaryView::orderToken).toList();
        assertThat(p1Tokens).doesNotContain(p2.items().get(0).orderToken());
    }

    @Test
    void onlyOwnOrders() {
        long owner = newUser().getId();
        long other = newUser().getId();
        seedVet(owner, 50000);
        assertThat(orderCenter.listOrders(other, null, null, 20).items()).isEmpty();
    }
}

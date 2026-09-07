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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
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

        OrderPage page = orderCenter.listOrders(userId, null, null, 20, true);

        assertThat(page.items()).hasSize(3);
        // 倒序：topup(-10s) > ai(-20s) > vet(-30s)
        assertThat(page.items()).extracting(OrderSummaryView::orderType)
                .containsExactly("PAWCOIN_TOPUP", "AI_UNLOCK", "VET_CONSULT");
        assertThat(page.items()).allSatisfy(v -> assertThat(v.statusColor()).isEqualTo("SUCCESS"));
        assertThat(page.hasMore()).isFalse();
    }

    /**
     * 🔴🔴 同刻订单不得在翻页时丢失（2026-08-18 修，{@code action_items: ORDER-CENTER-CURSOR-TIE}）。
     *
     * <p><b>原缺陷两层</b>：① 游标是 {@code createdAt} <b>截断到毫秒</b>，查询是严格 {@code <} ——
     * 末条是 {@code .123456} 时游标写成 {@code .123}，落在 {@code [.123000, .123456]} 之间的订单
     * <b>被永久跳过</b>；② 同刻订单（跨源）整组被跳过。
     *
     * <p><b>这条测试把 6 单的时间戳写成同一个值</b>，让缺陷从「要撞运气」变成「必现」：
     * 6 单跨 3 个源（2 兽医 + 2 AI + 2 充值），{@code limit=2} 逐页翻完，
     * 断言<b>一单不多、一单不少</b> —— 这同时覆盖了「同源同刻」和「跨源同刻」两种情况。
     */
    @Test
    @DisplayName("🔴🔴 6 单时间戳完全相同（跨 3 源）→ 逐页翻完，一单不多一单不少")
    void pagination_doesNotSkipOrdersSharingTheSameInstant() {
        long userId = newUser().getId();
        Instant same = Instant.now().minus(5, ChronoUnit.MINUTES);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ConsultOrder v = seedVet(userId, 50000 + i);
            setCreatedAt("consult_orders", "order_token", v.getOrderToken(), same);
            expected.add(v.getOrderToken());
            AiConsultOrder a = seedAi(userId, 5000 + i);
            setCreatedAt("ai_consult_orders", "order_token", a.getOrderToken(), same);
            expected.add(a.getOrderToken());
            PaymentIntent t = seedTopup(userId, 25000 + i);
            setCreatedAt("payment_intents", "public_token", t.getPublicToken(), same);
            expected.add(t.getPublicToken());
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 10; guard++) {
            OrderPage page = orderCenter.listOrders(userId, null, cursor, 2, true);
            page.items().forEach(v -> seen.add(v.orderToken()));
            if (!page.hasMore()) {
                break;
            }
            cursor = page.nextCursor();
            assertThat(cursor).as("hasMore=true 就必须给得出游标").isNotBlank();
        }

        assertThat(seen).as("🔴 有订单在翻页时丢了 —— 用户再也看不到它")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(seen).as("🔴 同刻订单没有确定顺序时，同一单会在两页里各出现一次")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("🔒 游标是不透明串，且不含明文顺序主键")
    void cursor_isOpaqueAndCarriesNoPlainId() {
        long userId = newUser().getId();
        ConsultOrder a = seedVet(userId, 10000);
        seedVet(userId, 20000);
        seedVet(userId, 30000);

        OrderPage p1 = orderCenter.listOrders(userId, null, null, 2, true);
        assertThat(p1.hasMore()).isTrue();
        String cursor = p1.nextCursor();
        // 🔒 不外泄顺序主键：订单对外一律用不可枚举 token（架构护栏）
        assertThat(cursor).doesNotContain(String.valueOf(a.getId()));
        // 也不是「一眼能看懂的时间戳」——它是 base64url 的复合键
        assertThat(cursor).doesNotContain(":");
        // 但必须仍是个能用的游标（不是把功能删了换绿）
        assertThat(orderCenter.listOrders(userId, null, cursor, 2, true).items()).hasSize(1);
    }

    @Test
    void typeFilter_onlyThatSource() {
        long userId = newUser().getId();
        seedVet(userId, 50000);
        seedAi(userId, 5000);
        seedTopup(userId, 25000);

        OrderPage vetOnly = orderCenter.listOrders(userId, "VET_CONSULT", null, 20, false);
        assertThat(vetOnly.items()).hasSize(1);
        assertThat(vetOnly.items().get(0).orderType()).isEqualTo("VET_CONSULT");
    }

    @Test
    void refundingOrder_mapsToInfoColor_notError() {
        long userId = newUser().getId();
        ConsultOrder vet = seedVet(userId, 50000);
        jdbc.update("UPDATE consult_orders SET status = 'REFUNDING' WHERE id = ?", vet.getId());

        OrderPage page = orderCenter.listOrders(userId, "VET_CONSULT", null, 20, false);
        assertThat(page.items().get(0).statusCode()).isEqualTo("REFUNDING");
        assertThat(page.items().get(0).statusColor()).isEqualTo("INFO"); // 退款中蓝非红
    }

    @Test
    void consultRequests_notInOrderCenter() {
        long userId = newUser().getId();
        seedVet(userId, 50000); // 1 已付订单
        // 待接单请求（A-5：取消即删、不进订单中心）
        requests.save(ConsultRequest.queue(userId, 1L, "req-" + n(), Instant.now().plusSeconds(60)));

        OrderPage page = orderCenter.listOrders(userId, null, null, 20, true);
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

        OrderPage p1 = orderCenter.listOrders(userId, null, null, 2, true);
        assertThat(p1.items()).hasSize(2);
        assertThat(p1.hasMore()).isTrue();
        assertThat(p1.nextCursor()).isNotNull();

        OrderPage p2 = orderCenter.listOrders(userId, null, p1.nextCursor(), 2, true);
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
        assertThat(orderCenter.listOrders(other, null, null, 20, true).items()).isEmpty();
    }
}

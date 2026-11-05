package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.shared.pay.ChargeQueryResult;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shop.coupon.domain.CouponReleaseMode;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.refund.CancelReason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 3.3 AC3 ⑤ / AC4 —— 等码失效、关单前最后查单、查询失败不关单。 */
class ShopOrderCloseTest {

    private static final long UID = 9L;

    private final ShopPaymentTestRig rig = new ShopPaymentTestRig();
    private final ShopOrderPaymentService service = rig.service;

    /** 建于 {@code minutesAgo} 分钟前的新模式待付款单（币 {@code coin}）。 */
    private ShopOrder order(long id, long minutesAgo, long coin) {
        ShopOrder o = ShopPaymentTestRig.createdAt(
                ShopOrderTestOrders.newModeOrder(id, UID, coin, CheckoutSource.CART),
                Instant.now().minus(Duration.ofMinutes(minutesAgo)));
        when(rig.orders.findById(id)).thenReturn(Optional.of(o));
        return o;
    }

    /** 该单已出码意图：码失效于「现在 + {@code providerMinutesFromNow} 分钟」。 */
    private PaymentIntent charged(ShopOrder o, long intentId, long providerMinutesFromNow) {
        PaymentIntent pi = ShopPaymentTestRig.intent(intentId, "pi-" + intentId,
                o.getCreatedAt().plus(Duration.ofMinutes(1)), "gw-" + intentId,
                Instant.now().plus(Duration.ofMinutes(providerMinutesFromNow)));
        return pi;
    }

    private void candidates(Long... ids) {
        when(rig.orders.findClosableCandidateIds(any(), anyInt())).thenReturn(List.of(ids));
    }

    private static PaymentCallback cb(PaymentIntent pi, GatewayStatus st) {
        return new PaymentCallback(pi.getPublicToken(), pi.getGatewayRef(), st, Map.of());
    }

    @Test
    @DisplayName("🔴 订单 61 分钟、码 63 分钟（仍有效）→ 不关、不查")
    void closeSkipsOrderWhoseCodeStillValid() {
        ShopOrder o = order(1L, 61, 0L);
        PaymentIntent pi = charged(o, 10L, 2);
        when(rig.intents.findByShopOrderId(1L)).thenReturn(List.of(pi));
        candidates(1L);

        assertThat(service.closeOverdue(50)).isZero();

        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        verify(rig.reconciliation, never()).query(any());
        assertThat(ShopOrderPaymentService.isClosable(o, List.of(pi), Instant.now())).isFalse();
    }

    @Test
    @DisplayName("存量意图（provider_expires_at 空）按 created_at + 65 分钟兜底；未出码意图不参与判据")
    void legacyIntentFallsBackToCreatedPlus65() {
        ShopOrder o = order(2L, 70, 0L);
        Instant now = Instant.now();
        PaymentIntent young = ShopPaymentTestRig.intent(20L, "pi-20", now.minus(Duration.ofMinutes(64)), "gw-20", null);
        PaymentIntent old = ShopPaymentTestRig.intent(21L, "pi-21", now.minus(Duration.ofMinutes(66)), "gw-21", null);
        PaymentIntent noCode = ShopPaymentTestRig.intent(22L, "pi-22", now.minus(Duration.ofMinutes(1)), null, null);
        assertThat(ShopOrderPaymentService.isClosable(o, List.of(young), now)).isFalse();
        assertThat(ShopOrderPaymentService.isClosable(o, List.of(old, noCode), now)).isTrue();
        // 订单窗未过 → 不可关
        ShopOrder inWindow = order(3L, 30, 0L);
        assertThat(ShopOrderPaymentService.isClosable(inWindow, List.of(), now)).isFalse();
    }

    @Test
    @DisplayName("从未出码（无意图）→ 不查单、直接关：releaseUnpaid(PAYMENT_TIMEOUT)，退币、退券")
    void noIntentClosesDirectly() {
        ShopOrder o = order(4L, 61, 30_000L);
        candidates(4L);

        assertThat(service.closeOverdue(50)).isEqualTo(1);

        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(o.getCancelReason()).isEqualTo(CancelReason.PAYMENT_TIMEOUT);
        verify(rig.reconciliation, never()).query(any());
        verify(rig.wallet).credit(UID, 30_000L, PawCoinTxnType.REFUND, "SHOP_ORDER_VOID", 4L,
                "shop-order-void:tok4");
        verify(rig.coupons).releaseOnCancel(eq(4L), eq(CouponReleaseMode.RETURN), any());
        verify(rig.inventory).release(11L, 2);
        verify(rig.redis).delete("shop:close-query-fail:4");
    }

    @Test
    @DisplayName("🔴 查到 PAID → applyResult，本单不关，清失败计数")
    void paidQueryAppliesAndKeepsOrder() {
        ShopOrder o = order(5L, 76, 0L);
        PaymentIntent pi = charged(o, 50L, -1);
        when(rig.intents.findByShopOrderId(5L)).thenReturn(List.of(pi));
        PaymentCallback paid = cb(pi, GatewayStatus.PAID);
        when(rig.reconciliation.query(any())).thenReturn(ChargeQueryResult.paid(paid));
        candidates(5L);

        assertThat(service.closeOverdue(50)).isZero();

        verify(rig.intents).applyResult(paid);
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        verify(rig.inventory, never()).release(anyLong(), anyInt());
        verify(rig.redis).delete("shop:close-query-fail:5");
    }

    @Test
    @DisplayName("全部网关明确 EXPIRED / FAILED → 先逐个 applyResult 推进意图，再关单（PAYMENT_TIMEOUT）")
    void allTerminalCloses() {
        ShopOrder o = order(6L, 76, 0L);
        PaymentIntent a = charged(o, 60L, -5);
        PaymentIntent b = charged(o, 61L, -1);
        when(rig.intents.findByShopOrderId(6L)).thenReturn(List.of(a, b));
        PaymentCallback ea = cb(a, GatewayStatus.EXPIRED);
        PaymentCallback fb = cb(b, GatewayStatus.FAILED);
        when(rig.reconciliation.query(a)).thenReturn(ChargeQueryResult.notPaid(ea));
        when(rig.reconciliation.query(b)).thenReturn(ChargeQueryResult.notPaid(fb));
        candidates(6L);

        assertThat(service.closeOverdue(50)).isEqualTo(1);

        var inOrder = org.mockito.Mockito.inOrder(rig.intents, rig.inventory);
        inOrder.verify(rig.intents).applyResult(ea);
        inOrder.verify(rig.intents).applyResult(fb);
        inOrder.verify(rig.inventory).release(11L, 2);
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(o.getCancelReason()).isEqualTo(CancelReason.PAYMENT_TIMEOUT);
        // AC4.2：该单全部仍 PENDING 的意图都作废（mock 下 applyResult 未改状态，两张都仍 PENDING）
        verify(rig.intents).failByToken("pi-60", "TIMEOUT");
        verify(rig.intents).failByToken("pi-61", "TIMEOUT");
    }

    @Test
    @DisplayName("🔴 S-1：任一 UNKNOWN（查询失败）→ 不关单、计数 +1（首次设 24 小时 TTL）")
    void unknownHoldsAndBumps() {
        ShopOrder o = order(7L, 76, 0L);
        PaymentIntent a = charged(o, 70L, -5);
        PaymentIntent b = charged(o, 71L, -1);
        when(rig.intents.findByShopOrderId(7L)).thenReturn(List.of(a, b));
        when(rig.reconciliation.query(a)).thenReturn(ChargeQueryResult.notPaid(cb(a, GatewayStatus.EXPIRED)));
        when(rig.reconciliation.query(b)).thenReturn(ChargeQueryResult.unknown());
        when(rig.valueOps.increment("shop:close-query-fail:7")).thenReturn(1L);
        candidates(7L);

        assertThat(service.closeOverdue(50)).isZero();

        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        verify(rig.intents, never()).applyResult(any());
        verify(rig.redis).expire("shop:close-query-fail:7", Duration.ofHours(24));
        verify(rig.alerts, never()).alert(anyString());
    }

    @Test
    @DisplayName("网关仍 PENDING（未决，非未付）→ 不关单")
    void gatewayPendingHolds() {
        ShopOrder o = order(8L, 76, 0L);
        PaymentIntent a = charged(o, 80L, -5);
        when(rig.intents.findByShopOrderId(8L)).thenReturn(List.of(a));
        when(rig.reconciliation.query(a)).thenReturn(ChargeQueryResult.notPaid(cb(a, GatewayStatus.PENDING)));
        when(rig.valueOps.increment(anyString())).thenReturn(2L);
        candidates(8L);

        assertThat(service.closeOverdue(50)).isZero();
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        verify(rig.inventory, never()).release(anyLong(), anyInt());
    }

    @Test
    @DisplayName("连续失败计数到 10 发飞书、11 不发、20 再发（文案只含展示号与次数）")
    void alertsEveryTenFailures() {
        ShopOrder o = order(9L, 76, 0L);
        PaymentIntent a = charged(o, 90L, -5);
        when(rig.intents.findByShopOrderId(9L)).thenReturn(List.of(a));
        when(rig.reconciliation.query(a)).thenReturn(ChargeQueryResult.unknown());
        when(rig.valueOps.increment("shop:close-query-fail:9")).thenReturn(10L, 11L, 20L);
        candidates(9L);

        service.closeOverdue(50);
        verify(rig.alerts, times(1)).alert(
                "电商订单关单前查单连续失败 10 次：TOKO-9，请检查 GemPay（多单同时出现时关单队列可能被堵）");
        service.closeOverdue(50);
        verify(rig.alerts, times(1)).alert(anyString());
        service.closeOverdue(50);
        verify(rig.alerts).alert(contains("20 次：TOKO-9"));
        verify(rig.alerts, times(2)).alert(anyString());
    }

    @Test
    @DisplayName("closeOne 复核：订单已被并发推进（已付）→ no-op 返回 false")
    void closeOneRecheckNoop() {
        ShopOrder o = order(10L, 76, 0L);
        o.markPaid(Instant.now());
        assertThat(service.closeOne(10L)).isFalse();
        verify(rig.inventory, never()).release(anyLong(), anyInt());

        ShopOrder valid = order(11L, 61, 0L);
        when(rig.intents.findByShopOrderId(11L)).thenReturn(List.of(charged(valid, 110L, 3)));
        assertThat(service.closeOne(11L)).as("码仍有效").isFalse();
    }

    @Test
    @DisplayName("单笔异常不阻断下一笔")
    void oneFailureDoesNotBlockOthers() {
        when(rig.orders.findById(12L)).thenThrow(new IllegalStateException("boom"));
        ShopOrder ok = order(13L, 61, 0L);
        candidates(12L, 13L);

        assertThat(service.closeOverdue(50)).isEqualTo(1);
        assertThat(ok.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("意图已 PAID 而订单仍待支付（不应出现）→ 绝不关单、交人工")
    void paidIntentNeverClosed() {
        ShopOrder o = order(14L, 76, 0L);
        PaymentIntent pi = charged(o, 140L, -5);
        pi.markPaid(null);
        when(rig.intents.findByShopOrderId(14L)).thenReturn(List.of(pi));
        when(rig.valueOps.increment(anyString())).thenReturn(1L);
        candidates(14L);

        assertThat(service.closeOverdue(50)).isZero();
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        verify(rig.reconciliation, never()).query(any());
    }

    @Test
    @DisplayName("候选 SQL：订单窗已过 + NOT EXISTS 未失效码（COALESCE 兜底 65 分钟）+ 按 expires_at 升序")
    void candidateSqlText() {
        String sql = com.tailtopia.shop.order.repository.ShopOrderRepository.CLOSABLE_CANDIDATES_SQL
                .replaceAll("\\s+", " ");
        assertThat(sql).contains("o.status = 'PENDING_PAYMENT' AND o.expires_at < :now")
                .contains("pi.shop_order_id = o.id AND pi.gateway_ref IS NOT NULL")
                .contains("COALESCE(pi.provider_expires_at, pi.created_at + INTERVAL '65 minutes') >= :now")
                .contains("ORDER BY o.expires_at ASC")
                .contains("LIMIT :limit");
        assertThat(ShopOrderPaymentService.CLOSE_BATCH_LIMIT).isEqualTo(50);
    }

    @Test
    @DisplayName("复审③：closeOne 事务内重读到已 PAID 意图 → 不关单返回 false")
    void closeOneSkipsWhenIntentPaid() {
        ShopOrder o = order(15L, 76, 0L);
        PaymentIntent pi = charged(o, 150L, -5);
        pi.markPaid(null);
        when(rig.intents.findByShopOrderId(15L)).thenReturn(List.of(pi));

        assertThat(service.closeOne(15L)).isFalse();
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        verify(rig.inventory, never()).release(anyLong(), anyInt());
    }

    @Test
    @DisplayName("复审②：一批 HOLD 数 == limit → 额外告警「关单队列可能被堵」一次；未满不告警")
    void fullBatchHeldAlertsQueueBlocked() {
        ShopOrder a = order(16L, 76, 0L);
        ShopOrder b = order(17L, 76, 0L);
        PaymentIntent pa = charged(a, 160L, -5);
        PaymentIntent pb = charged(b, 170L, -5);
        when(rig.intents.findByShopOrderId(16L)).thenReturn(List.of(pa));
        when(rig.intents.findByShopOrderId(17L)).thenReturn(List.of(pb));
        when(rig.reconciliation.query(any())).thenReturn(ChargeQueryResult.unknown());
        when(rig.valueOps.increment(anyString())).thenReturn(1L);
        candidates(16L, 17L);

        service.closeOverdue(2);
        verify(rig.alerts, times(1)).alert(contains("关单队列可能被堵：本批 2 单全部查单未决"));

        service.closeOverdue(3);   // 候选 2 < limit 3 → 不告警
        verify(rig.alerts, times(1)).alert(contains("关单队列可能被堵"));
    }
}

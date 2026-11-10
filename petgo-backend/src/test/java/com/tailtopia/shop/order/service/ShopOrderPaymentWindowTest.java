package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/** L0：v1.4.2 shop Story 3.3 AC2 / AC3 ① ④ / AC7.2 —— 付款窗口、出码窗口与读单只读。 */
class ShopOrderPaymentWindowTest {

    private static final long UID = 9L;

    private final ShopPaymentTestRig rig = new ShopPaymentTestRig();
    private final ShopOrderPaymentService service = rig.service;

    /** 纯现金待付款单，建于 {@code minutesAgo} 分钟前。 */
    private ShopOrder order(long id, long minutesAgo) {
        ShopOrder o = ShopPaymentTestRig.createdAt(
                ShopOrderTestOrders.newModeOrder(id, UID, 0L, CheckoutSource.CART),
                Instant.now().minus(Duration.ofMinutes(minutesAgo)));
        when(rig.orders.findByPublicTokenAndUserId("tok" + id, UID)).thenReturn(Optional.of(o));
        when(rig.orders.findById(id)).thenReturn(Optional.of(o));
        return o;
    }

    /** 让 ensureIntent 建出一张新意图 {@code token}（无网关单号）。 */
    private PaymentIntent stubNewIntent(String token) {
        PaymentIntent fresh = PaymentIntent.create(UID, PaymentPurpose.SHOP_ORDER, PayChannel.QRIS, 220_000L, "IDR",
                token, Instant.now().plus(Duration.ofMinutes(65)));
        when(rig.intents.createIntent(eq(UID), eq(PaymentPurpose.SHOP_ORDER), eq(PayChannel.QRIS), anyLong(),
                anyString(), anyString(), any())).thenReturn(PaymentIntentResponse.of(fresh));
        when(rig.intents.findByToken(token)).thenReturn(Optional.of(fresh));
        when(rig.gateway.createCharge(any())).thenReturn(new ChargeResult("gw-" + token, "QR-" + token, Map.of()));
        return fresh;
    }

    private static void assertNotPayable(Runnable r, String detail) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(AppException.class, e -> {
            assertThat(e.getType()).isEqualTo(ErrorTypes.SHOP_ORDER_NOT_PAYABLE);
            assertThat(e.getStatus().value()).isEqualTo(409);
        }).hasMessageContaining(detail);
    }

    @Test
    @DisplayName("🔴 B1 ①：第 5 分钟出码 → 意图 TTL = 65 分钟（不是订单剩余 55 分钟）；attachCharge 传 PROVIDER_CODE_TTL；绑定 shop_order_id")
    void ensureIntentUsesProviderTtlNotOrderWindow() {
        ShopOrder o = order(1L, 5);
        stubNewIntent("pi-new");

        service.pay(UID, "tok1", null);

        verify(rig.intents).createIntent(eq(UID), eq(PaymentPurpose.SHOP_ORDER), eq(PayChannel.QRIS),
                eq(220_000L), eq("IDR"), eq("shop-order-pay:tok1"), eq(Duration.ofMinutes(65)));
        verify(rig.intents).attachCharge(eq("pi-new"), eq("gw-pi-new"), any(), eq(Duration.ofMinutes(65)));
        verify(rig.intents).bindShopOrder("pi-new", 1L);
        assertThat(o.getExpiresAt()).as("订单窗仍为建单 + 60 分钟").isEqualTo(o.getCreatedAt().plus(Duration.ofMinutes(60)));
        assertThat(ShopOrderPaymentService.PROVIDER_CODE_TTL).isEqualTo(Duration.ofMinutes(65));
        assertThat(ShopOrderPaymentService.QR_ISSUE_WINDOW).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("🔴 ocr L1：pay() 判可付后、建意图前订单被取消 → ensureIntent 事务内复核 409，不建意图不出码")
    void ensureIntentRechecksStatusInsideTx() {
        ShopOrder snapshot = order(7L, 5);
        ShopOrder cancelled = ShopPaymentTestRig.createdAt(
                ShopOrderTestOrders.newModeOrder(7L, UID, 0L, CheckoutSource.CART), snapshot.getCreatedAt());
        cancelled.markCancelled(com.tailtopia.shop.order.refund.CancelReason.USER_UNPAID_CANCEL, Instant.now());
        when(rig.orders.findById(7L)).thenReturn(Optional.of(cancelled));
        stubNewIntent("pi-7");

        assertThatThrownBy(() -> service.pay(UID, "tok7", null))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
        verify(rig.intents, never()).createIntent(anyLong(), any(), any(), anyLong(), anyString(), anyString(), any());
        verify(rig.gateway, never()).createCharge(any());
    }

    @Test
    @DisplayName("🔴 S-7：订单窗已过（宽限期）→ 409 shop-order-not-payable「订单已超过付款时间」，不出码")
    void payRejectsAfterOrderWindow() {
        order(2L, 61);
        assertNotPayable(() -> service.pay(UID, "tok2", null), "订单已超过付款时间");
        verify(rig.gateway, never()).createCharge(any());
    }

    @Test
    @DisplayName("当前意图码仍有效 → 直接返回同一个码，不向网关再下单（即便已过 10 分钟出码窗口）")
    void payReusesValidCodeWithoutNewCharge() {
        ShopOrder o = order(3L, 20);
        PaymentIntent cur = ShopPaymentTestRig.intent(30L, "pi-3", o.getCreatedAt().plus(Duration.ofMinutes(2)),
                "gw-3", o.getCreatedAt().plus(Duration.ofMinutes(67)));
        o.attachPaymentIntent("pi-3");
        when(rig.intents.findByToken("pi-3")).thenReturn(Optional.of(cur));

        ShopOrderPaymentService.PayResult r = service.pay(UID, "tok3", null);

        assertThat(r.paymentIntentToken()).isEqualTo("pi-3");
        assertThat(r.payload()).isEqualTo("QR-pi-3");
        assertThat(r.expiresAt()).isEqualTo(o.getExpiresAt());
        verify(rig.gateway, never()).createCharge(any());
        verify(rig.intents, never()).createIntent(anyLong(), any(), any(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("🔴 I3：建单 11 分钟后首次出码 → 409「订单已无法支付」")
    void payRejectsNewCodeAfterTenMinutes() {
        order(4L, 11);
        assertNotPayable(() -> service.pay(UID, "tok4", null), "订单已无法支付");
        verify(rig.gateway, never()).createCharge(any());
    }

    @Test
    @DisplayName("首次出码失败（意图 FAILED）后第 7 分钟重试 → 出新码")
    void payAllowsRetryWithinTenMinutesAfterChargeFailure() {
        ShopOrder o = order(5L, 7);
        PaymentIntent failed = ShopPaymentTestRig.intent(50L, "pi-5a", o.getCreatedAt().plus(Duration.ofMinutes(5)),
                null, null);
        failed.markFailed(Map.of("reason", "GATEWAY_CHARGE_FAILED"));
        o.attachPaymentIntent("pi-5a");
        when(rig.intents.findByToken("pi-5a")).thenReturn(Optional.of(failed));
        stubNewIntent("pi-5b");

        ShopOrderPaymentService.PayResult r = service.pay(UID, "tok5", null);

        assertThat(r.payload()).isEqualTo("QR-pi-5b");
        verify(rig.intents).attachCharge(eq("pi-5b"), eq("gw-pi-5b"), any(), eq(Duration.ofMinutes(65)));
    }

    @Test
    @DisplayName("🔴 B1 ④：61 分钟读单 → 不关单（不调 releaseUnpaid 链路、不调 failByToken），requireOwn 只读事务")
    void requireOwnDoesNotCancelOverdueOrder() throws Exception {
        ShopOrder o = order(6L, 61);
        o.attachPaymentIntent("pi-6");

        ShopOrder read = service.requireOwn(UID, "tok6");

        assertThat(read.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        assertThat(read.displayStatusAt(Instant.now())).isEqualTo(ShopOrderStatus.CANCELLED);
        verify(rig.intents, never()).failByToken(anyString(), anyString());
        verify(rig.inventory, never()).release(anyLong(), org.mockito.ArgumentMatchers.anyInt());
        Transactional tx = ShopOrderPaymentService.class.getMethod("requireOwn", long.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(tx.readOnly()).isTrue();
        assertThat(java.util.Arrays.stream(ShopOrderPaymentService.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .as("懒关单方法已删除").doesNotContain("expireIfOverdue", "cancelOverdue", "cancelOneOverdue");
    }

    @Test
    @DisplayName("🔴 拍板④：宽限期内用户取消 = no-op（不释放、不作废意图、不改状态）")
    void cancelInGracePeriodIsNoop() {
        ShopOrder o = order(7L, 62);
        o.attachPaymentIntent("pi-7");

        service.cancel(UID, "tok7");

        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        verify(rig.intents, never()).failByToken(anyString(), anyString());
        verify(rig.inventory, never()).release(anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(rig.wallet, never()).credit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("窗口内用户取消照常（USER_CANCEL）")
    void cancelInsideWindowStillCancels() {
        ShopOrder o = order(8L, 5);
        o.attachPaymentIntent("pi-8");
        service.cancel(UID, "tok8");
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
        verify(rig.intents).failByToken("pi-8", "USER_CANCEL");
    }

    @Test
    @DisplayName("AC7.2：canPay 与 /pay 放行同一判定 —— 可付的不抛 not-payable，不可付的抛")
    void canPayMatchesPayGate() {
        Instant now = Instant.now();
        // 窗口内新单 → 可付
        ShopOrder fresh = order(11L, 3);
        assertThat(ShopOrderPaymentService.canPay(fresh, null, now)).isTrue();
        // 11 分钟、无码 → 不可付
        ShopOrder late = order(12L, 11);
        assertThat(ShopOrderPaymentService.canPay(late, null, now)).isFalse();
        assertNotPayable(() -> service.pay(UID, "tok12", null), "无法支付");
        // 20 分钟、码有效 → 可付（复用）
        ShopOrder reuse = order(13L, 20);
        PaymentIntent cur = ShopPaymentTestRig.intent(130L, "pi-13", reuse.getCreatedAt(), "gw-13",
                now.plus(Duration.ofMinutes(40)));
        assertThat(ShopOrderPaymentService.payGate(reuse, cur, now))
                .isEqualTo(ShopOrderPaymentService.PayGate.REUSE);
        assertThat(ShopOrderPaymentService.canPay(reuse, cur, now)).isTrue();
        // 宽限期 → 不可付
        ShopOrder grace = order(14L, 61);
        assertThat(ShopOrderPaymentService.canPay(grace, cur, now)).isFalse();
        assertNotPayable(() -> service.pay(UID, "tok14", null), "超过付款时间");
        // 已付 → 不可付
        ShopOrder paid = order(15L, 3);
        paid.markPaid(now);
        assertThat(ShopOrderPaymentService.canPay(paid, null, now)).isFalse();
    }

    @Test
    @DisplayName("V1.4.2 验收修复 BUG-142-02：网关拒绝（PayException）→ 502 payment-gateway，意图置 FAILED，不再裸 500")
    void gatewayDeclineMapsTo502() {
        order(21L, 1);
        stubNewIntent("pi-21");
        when(rig.gateway.createCharge(any())).thenThrow(new com.tailtopia.shared.pay.PayException("支付网关收款失败"));

        assertThatThrownBy(() -> service.pay(UID, "tok21", null))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(502);
                    assertThat(e.getType()).isEqualTo(ErrorTypes.PAYMENT_GATEWAY);
                });
        verify(rig.intents).failChargeAttempt("pi-21");
    }
}

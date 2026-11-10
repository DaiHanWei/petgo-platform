package com.tailtopia.pay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.ChargeQueryResult;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shared.pay.PaymentGateway;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * L0：收款对账（轮询通道）。v1.4.2 shop Story 3.3（AC5.5）：网关调用移出事务、三态结果、电商终态意图照查。
 */
@ExtendWith(MockitoExtension.class)
class PaymentReconciliationServiceTest {

    @Mock
    private PaymentGateway gateway;
    @Mock
    private PaymentIntentRepository intents;
    @Mock
    private PaymentIntentService paymentIntentService;

    @InjectMocks
    private PaymentReconciliationService service;

    private PaymentIntent withRef(PaymentPurpose purpose, String token, String ref) {
        PaymentIntent intent = PaymentIntent.create(1L, purpose, PayChannel.QRIS, 10000L, "IDR", token);
        if (ref != null) {
            intent.attachGatewayRef(ref, null);
        }
        return intent; // 仍 PENDING
    }

    @Test
    @DisplayName("PENDING + 有 ref → 只读读意图 → 事务外 queryCharge → applyResult（顺序钉死）")
    void reconcilePendingQueriesGatewayAndFunnelsResult() {
        String token = "TOKEN-1";
        PaymentIntent intent = withRef(PaymentPurpose.PAWCOIN_TOPUP, token, "GP-REF-1");
        when(paymentIntentService.findByToken(token)).thenReturn(Optional.of(intent));
        PaymentCallback cb = new PaymentCallback(token, "GP-REF-1", GatewayStatus.PAID, Map.of());
        when(gateway.queryCharge("GP-REF-1")).thenReturn(ChargeQueryResult.paid(cb));

        service.reconcile(token);

        InOrder o = inOrder(paymentIntentService, gateway);
        o.verify(paymentIntentService).findByToken(token);
        o.verify(gateway).queryCharge("GP-REF-1");
        o.verify(paymentIntentService).applyResult(cb);
        verify(gateway, times(1)).queryCharge("GP-REF-1");
    }

    @Test
    @DisplayName("🔴 reconcile / query / reconcilePending 都不带 @Transactional（网关调用不在事务内，P-7）")
    void reconcileIsNotTransactional() throws Exception {
        for (String name : new String[] {"reconcile", "query", "reconcilePending"}) {
            for (Method m : PaymentReconciliationService.class.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    assertThat(m.isAnnotationPresent(Transactional.class)).as(name).isFalse();
                }
            }
        }
        assertThat(PaymentReconciliationService.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("非电商终态（PAWCOIN_TOPUP 已 PAID）→ 不查不推进（原意保留）")
    void reconcileTerminalIntentSkipsGateway() {
        String token = "TOKEN-2";
        PaymentIntent intent = withRef(PaymentPurpose.PAWCOIN_TOPUP, token, "GP-REF-2");
        intent.markExpired(null); // 已终态（非电商）
        when(paymentIntentService.findByToken(token)).thenReturn(Optional.of(intent));

        service.reconcile(token);

        verify(gateway, never()).queryCharge(any());
        verify(paymentIntentService, never()).applyResult(any());
    }

    @Test
    @DisplayName("🔴 电商 FAILED 意图照查（找回迟到款）；查到 PAID → applyResult")
    void reconcileShopFailedIntentStillQueries() {
        String token = "TOKEN-4";
        PaymentIntent intent = withRef(PaymentPurpose.SHOP_ORDER, token, "GP-REF-4");
        intent.markFailed(Map.of("reason", "TIMEOUT"));
        when(paymentIntentService.findByToken(token)).thenReturn(Optional.of(intent));
        PaymentCallback cb = new PaymentCallback(token, "GP-REF-4", GatewayStatus.PAID, Map.of());
        when(gateway.queryCharge("GP-REF-4")).thenReturn(ChargeQueryResult.paid(cb));

        service.reconcile(token);

        verify(paymentIntentService).applyResult(cb);
    }

    @Test
    @DisplayName("电商已 PAID → 不查")
    void reconcileShopPaidSkips() {
        String token = "TOKEN-5";
        PaymentIntent intent = withRef(PaymentPurpose.SHOP_ORDER, token, "GP-REF-5");
        intent.markPaid(null);
        when(paymentIntentService.findByToken(token)).thenReturn(Optional.of(intent));

        service.reconcile(token);

        verify(gateway, never()).queryCharge(any());
    }

    @Test
    @DisplayName("UNKNOWN / 网关仍 PENDING → 不推进；FAILED → 推进")
    void reconcileOnlyAdvancesOnPaidOrTerminal() {
        String token = "TOKEN-6";
        PaymentIntent intent = withRef(PaymentPurpose.SHOP_ORDER, token, "GP-REF-6");
        when(paymentIntentService.findByToken(token)).thenReturn(Optional.of(intent));

        when(gateway.queryCharge("GP-REF-6")).thenReturn(ChargeQueryResult.unknown());
        service.reconcile(token);
        PaymentCallback pending = new PaymentCallback(token, "GP-REF-6", GatewayStatus.PENDING, Map.of());
        when(gateway.queryCharge("GP-REF-6")).thenReturn(ChargeQueryResult.notPaid(pending));
        service.reconcile(token);
        verify(paymentIntentService, never()).applyResult(any());

        PaymentCallback failed = new PaymentCallback(token, "GP-REF-6", GatewayStatus.FAILED, Map.of());
        when(gateway.queryCharge("GP-REF-6")).thenReturn(ChargeQueryResult.notPaid(failed));
        service.reconcile(token);
        verify(paymentIntentService).applyResult(failed);
    }

    @Test
    @DisplayName("无 gateway_ref → 不查；query 返回 unknown")
    void reconcileWithoutGatewayRefSkipsGateway() {
        String token = "TOKEN-3";
        PaymentIntent intent = withRef(PaymentPurpose.SHOP_ORDER, token, null); // 未下单，无 ref
        when(paymentIntentService.findByToken(token)).thenReturn(Optional.of(intent));

        service.reconcile(token);

        verify(gateway, never()).queryCharge(any());
        verify(paymentIntentService, never()).applyResult(any());
        assertThat(service.query(intent).outcome()).isEqualTo(ChargeQueryResult.Outcome.UNKNOWN);
    }

    @Test
    void reconcileUnknownTokenIsNotFound() {
        when(paymentIntentService.findByToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reconcile("nope"))
                .isInstanceOf(AppException.class);
    }
}

package com.tailtopia.pay.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shared.pay.PaymentGateway;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0：收款对账（轮询通道）。未终态且有网关订单号 → 查网关并经单一收口推进；终态 / 无 ref → 不查不推进。
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

    private PaymentIntent pendingWithRef(String token, String ref) {
        PaymentIntent intent = PaymentIntent.create(
                1L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 10000L, "IDR", token);
        if (ref != null) {
            intent.attachGatewayRef(ref, null);
        }
        return intent; // 仍 PENDING
    }

    @Test
    void reconcilePendingQueriesGatewayAndFunnelsResult() {
        String token = "TOKEN-1";
        PaymentIntent intent = pendingWithRef(token, "GP-REF-1");
        when(intents.findByPublicToken(token)).thenReturn(Optional.of(intent));
        PaymentCallback cb = new PaymentCallback(token, "GP-REF-1", GatewayStatus.PAID, Map.of());
        when(gateway.queryCharge("GP-REF-1")).thenReturn(Optional.of(cb));

        service.reconcile(token);

        verify(gateway, times(1)).queryCharge("GP-REF-1");
        // 结果交同一单一收口（保双通道去重）。
        verify(paymentIntentService, times(1)).applyCallback(cb);
    }

    @Test
    void reconcileTerminalIntentSkipsGateway() {
        String token = "TOKEN-2";
        PaymentIntent intent = pendingWithRef(token, "GP-REF-2");
        intent.markPaid(null); // 已终态
        when(intents.findByPublicToken(token)).thenReturn(Optional.of(intent));

        service.reconcile(token);

        verify(gateway, never()).queryCharge(any());
        verify(paymentIntentService, never()).applyCallback(any());
    }

    @Test
    void reconcileWithoutGatewayRefSkipsGateway() {
        String token = "TOKEN-3";
        PaymentIntent intent = pendingWithRef(token, null); // 未下单，无 ref
        when(intents.findByPublicToken(token)).thenReturn(Optional.of(intent));

        service.reconcile(token);

        verify(gateway, never()).queryCharge(any());
        verify(paymentIntentService, never()).applyCallback(any());
    }

    @Test
    void reconcileUnknownTokenIsNotFound() {
        when(intents.findByPublicToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reconcile("nope"))
                .isInstanceOf(AppException.class);
    }
}

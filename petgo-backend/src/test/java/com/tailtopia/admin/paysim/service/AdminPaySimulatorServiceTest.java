package com.tailtopia.admin.paysim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.paysim.service.AdminPaySimulatorService.Target;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** L0（stag 专用工具）：模拟支付回调走单一收口 + 审计。纯 Mockito。 */
class AdminPaySimulatorServiceTest {

    private final PaymentIntentRepository intents = Mockito.mock(PaymentIntentRepository.class);
    private final PaymentIntentService paymentIntentService = Mockito.mock(PaymentIntentService.class);
    private final AdminAuditService auditService = Mockito.mock(AdminAuditService.class);
    private final AdminPaySimulatorService svc =
            new AdminPaySimulatorService(intents, paymentIntentService, auditService);

    @Test
    void simulateSuccessAppliesPaidCallbackAndAudits() {
        when(intents.findByPublicToken("tok")).thenReturn(Optional.of(
                PaymentIntent.create(5L, PaymentPurpose.VET_CONSULT, PayChannel.QRIS, 50000L, "IDR", "tok")));

        svc.simulate("tok", Target.SUCCESS, 9L);

        ArgumentCaptor<PaymentCallback> cap = ArgumentCaptor.forClass(PaymentCallback.class);
        verify(paymentIntentService).applyCallback(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(GatewayStatus.PAID);
        assertThat(cap.getValue().orderId()).isEqualTo("tok");
        verify(auditService).record(eq(9L), eq("PAYMENT_CALLBACK_SIMULATED"),
                eq("PAYMENT_INTENT"), eq("tok"), anyString());
    }

    @Test
    void eachTargetMapsToGatewayStatus() {
        assertThat(Target.SUCCESS.gatewayStatus()).isEqualTo(GatewayStatus.PAID);
        assertThat(Target.FAILED.gatewayStatus()).isEqualTo(GatewayStatus.FAILED);
        assertThat(Target.EXPIRED.gatewayStatus()).isEqualTo(GatewayStatus.EXPIRED);
    }

    @Test
    void simulateUnknownTokenThrows() {
        when(intents.findByPublicToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.simulate("nope", Target.FAILED, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

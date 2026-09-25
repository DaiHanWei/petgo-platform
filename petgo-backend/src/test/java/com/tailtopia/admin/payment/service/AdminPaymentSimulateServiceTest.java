package com.tailtopia.admin.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.pay.GatewayStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/** L0：staging 支付模拟回调与审计同一事务（2026-09-25 code review #12）。 */
class AdminPaymentSimulateServiceTest {

    @Test
    @DisplayName("🔴 模拟回调与审计在同一个 @Transactional 方法里（审计失败则整笔回滚）")
    void callbackAndAuditShareOneTransaction() throws Exception {
        assertThat(AdminPaymentSimulateService.class
                .getMethod("simulate", long.class, String.class, GatewayStatus.class)
                .isAnnotationPresent(Transactional.class)).isTrue();

        PaymentIntentService intents = mock(PaymentIntentService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        new AdminPaymentSimulateService(intents, audit).simulate(1L, "tok", GatewayStatus.PAID);

        var order = inOrder(intents, audit);
        order.verify(intents).applyCallback(any());
        order.verify(audit).record(eq(1L), eq("PAYMENT_SIMULATE_CALLBACK"), eq("payment_intent"), eq("tok"), any());
    }
}

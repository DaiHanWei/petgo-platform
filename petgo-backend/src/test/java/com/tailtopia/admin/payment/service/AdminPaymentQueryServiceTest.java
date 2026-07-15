package com.tailtopia.admin.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.payment.dto.AdminPaymentRow;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0（Story 9.6，AB-8E）：支付记录跨类型行映射。纯 Mockito。 */
class AdminPaymentQueryServiceTest {

    @Test
    void mapsCrossTypePaymentsByUser() {
        PaymentIntentRepository repo = Mockito.mock(PaymentIntentRepository.class);
        AdminPaymentQueryService svc = new AdminPaymentQueryService(repo);
        when(repo.findByUserIdOrderByCreatedAtDesc(100L)).thenReturn(List.of(
                PaymentIntent.create(100L, PaymentPurpose.VET_CONSULT, PayChannel.QRIS, 50000L, "IDR", "t1"),
                PaymentIntent.create(100L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 10000L, "IDR", "t2")));

        List<AdminPaymentRow> rows = svc.byUser(100L);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(AdminPaymentRow::purpose)
                .containsExactly("VET_CONSULT", "PAWCOIN_TOPUP");
        assertThat(rows.get(0).amount()).isEqualTo(50000L);
        assertThat(rows.get(0).publicToken()).isEqualTo("t1");
    }
}

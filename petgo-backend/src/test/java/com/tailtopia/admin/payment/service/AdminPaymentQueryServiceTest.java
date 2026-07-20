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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

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
        assertThat(rows.get(0).userId()).isEqualTo(100L);
    }

    @Test
    void recentReturnsPagedGlobalLatestByCreatedAtDesc() {
        PaymentIntentRepository repo = Mockito.mock(PaymentIntentRepository.class);
        AdminPaymentQueryService svc = new AdminPaymentQueryService(repo);
        PageRequest pr = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(repo.findAll(pr)).thenReturn(new PageImpl<>(List.of(
                PaymentIntent.create(7L, PaymentPurpose.ID_HD, PayChannel.QRIS, 20000L, "IDR", "a"),
                PaymentIntent.create(9L, PaymentPurpose.AI_UNLOCK, PayChannel.QRIS, 15000L, "IDR", "b")),
                pr, 2));

        Page<AdminPaymentRow> result = svc.recent(0, 20);

        assertThat(result.getContent()).extracting(AdminPaymentRow::userId).containsExactly(7L, 9L);
        assertThat(result.getContent().get(0).purpose()).isEqualTo("ID_HD");
        assertThat(result.getTotalElements()).isEqualTo(2);
    }
}

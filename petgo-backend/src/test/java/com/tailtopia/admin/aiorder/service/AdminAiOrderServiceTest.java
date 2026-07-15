package com.tailtopia.admin.aiorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.aiorder.dto.AiRevenueSummary;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.triage.domain.AiConsultOrder;
import com.tailtopia.triage.domain.AiConsultOrderStatus;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Sort;

/** L0（Story 9.4）：AI 收入汇总口径（仅 COMPLETED）+ 渠道拆分 + CSV 组装。纯 Mockito。 */
class AdminAiOrderServiceTest {

    private AiConsultOrderRepository orders;
    private AdminAiOrderService svc;

    @BeforeEach
    void setUp() {
        orders = Mockito.mock(AiConsultOrderRepository.class);
        svc = new AdminAiOrderService(orders);
    }

    @Test
    void summaryCountsOnlyCompletedAsRevenue() {
        when(orders.sumAmountByStatus(AiConsultOrderStatus.COMPLETED)).thenReturn(30000L);
        when(orders.sumAmountByStatusAndChannel(AiConsultOrderStatus.COMPLETED, PayChannel.QRIS))
                .thenReturn(20000L);
        when(orders.sumAmountByStatusAndChannel(AiConsultOrderStatus.COMPLETED, PayChannel.PAWCOIN))
                .thenReturn(10000L);
        when(orders.countByStatus(AiConsultOrderStatus.COMPLETED)).thenReturn(3L);
        when(orders.countByStatus(AiConsultOrderStatus.PENDING_PAYMENT)).thenReturn(2L);
        when(orders.countByStatus(AiConsultOrderStatus.ABNORMAL)).thenReturn(1L);

        AiRevenueSummary s = svc.summary();

        assertThat(s.totalRevenue()).isEqualTo(30000L);
        assertThat(s.revenueQris()).isEqualTo(20000L);
        assertThat(s.revenuePawcoin()).isEqualTo(10000L);
        assertThat(s.completedCount()).isEqualTo(3L);
        assertThat(s.pendingCount()).isEqualTo(2L);   // 不计收入，仅计数
        assertThat(s.abnormalCount()).isEqualTo(1L);
    }

    @Test
    void exportCsvHasHeaderAndRow() {
        AiConsultOrder o = AiConsultOrder.completedPawCoin("ai-tok", 100L, 5L, 10000L);
        when(orders.findAll(Mockito.any(Sort.class))).thenReturn(List.of(o));

        String csv = svc.exportCsv();

        assertThat(csv).startsWith(
                "order_token,user_id,triage_task_id,amount,pay_channel,status,paid_at,created_at\n");
        assertThat(csv).contains("ai-tok,100,5,10000,PAWCOIN,COMPLETED,");
    }
}

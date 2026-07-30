package com.tailtopia.admin.aiorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.aiorder.dto.AiRevenueSummary;
import com.tailtopia.admin.aiorder.service.AdminAiOrderService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.triage.domain.AiConsultOrder;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（Story 9.4）：真 pg——AI 收入汇总只含 COMPLETED + 渠道拆分、订单列表/详情、CSV 导出。
 * 命名空间隔离由独立表 {@code ai_consult_orders} 天然保证（不掺 consult_orders）。
 */
class AdminAiOrderIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAiOrderService service;
    @Autowired
    private AiConsultOrderRepository orders;

    @Test
    void summaryCountsOnlyCompletedAndSplitsByChannel() {
        AiRevenueSummary before = service.summary();
        long n = SEQ.incrementAndGet();
        // 1 笔 PawCoin COMPLETED（10000）+ 1 笔 QRIS 待支付（不计收入）。
        orders.save(AiConsultOrder.completedPawCoin("ai-c-" + n, 100L + n, 5L, 10000L));
        orders.save(AiConsultOrder.pendingCash("ai-p-" + n, 200L + n, 6L, 10000L,
                PayChannel.QRIS, "intent-" + n));

        AiRevenueSummary after = service.summary();

        assertThat(after.totalRevenue()).isEqualTo(before.totalRevenue() + 10000L);
        assertThat(after.revenuePawcoin()).isEqualTo(before.revenuePawcoin() + 10000L);
        assertThat(after.completedCount()).isEqualTo(before.completedCount() + 1);
        assertThat(after.pendingCount()).isEqualTo(before.pendingCount() + 1);
    }

    @Test
    void listAndDetailAndExport() {
        long n = SEQ.incrementAndGet();
        AiConsultOrder o = orders.save(AiConsultOrder.completedPawCoin("ai-l-" + n, 300L + n, 7L, 10000L));

        assertThat(service.list()).extracting("orderToken").contains(o.getOrderToken());
        assertThat(service.detail(o.getOrderToken()).status()).isEqualTo("COMPLETED");

        String csv = service.exportCsv();
        assertThat(csv).startsWith("order_token,");
        assertThat(csv).contains(o.getOrderToken());
    }
}

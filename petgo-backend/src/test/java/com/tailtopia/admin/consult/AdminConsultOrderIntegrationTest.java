package com.tailtopia.admin.consult;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.consult.dto.AdminConsultOrderDetail;
import com.tailtopia.admin.consult.service.AdminConsultOrderService;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderVerifyStatus;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（Story 9.3）：真 Spring 上下文 + PostgreSQL——V79 schema validate（启动即验）、只读列表/详情、
 * 重播快照落库、待核查标记 + 审计（订单业务状态不变）、CSV 导出。
 */
class AdminConsultOrderIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminConsultOrderService service;
    @Autowired
    private ConsultOrderRepository orders;

    private ConsultOrder seedOrder(int rebroadcast) {
        long n = SEQ.incrementAndGet();
        ConsultOrder o = ConsultOrder.inProgress("adm-ord-" + n, 100L + n, 9L, 3L, 50000L,
                PayChannel.QRIS, null, 30000L, 60, 50000L, Instant.now());
        o.snapshotRebroadcast(rebroadcast);
        return orders.save(o);
    }

    @Test
    void listAndDetailAreReadOnlyWithStages() {
        ConsultOrder o = seedOrder(2);

        assertThat(service.list()).extracting("orderToken").contains(o.getOrderToken());

        AdminConsultOrderDetail d = service.detail(o.getOrderToken());
        assertThat(d.rebroadcastCount()).isEqualTo(2);
        assertThat(d.statusCode()).isEqualTo("IN_PROGRESS");
        assertThat(d.verifyStatus()).isEmpty();
    }

    @Test
    void markVerifyPersistsWithoutChangingBusinessStatus() {
        ConsultOrder o = seedOrder(0);

        service.markVerify(o.getOrderToken(), ConsultOrderVerifyStatus.TO_VERIFY, "待核查备注", 1L);

        ConsultOrder reloaded = orders.findByOrderToken(o.getOrderToken()).orElseThrow();
        assertThat(reloaded.getAdminVerifyStatus()).isEqualTo(ConsultOrderVerifyStatus.TO_VERIFY);
        assertThat(reloaded.getAdminVerifyBy()).isEqualTo(1L);
        // 业务状态不变（纯注记）。
        assertThat(reloaded.getStatus().name()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void exportCsvContainsHeaderAndSeededOrder() {
        ConsultOrder o = seedOrder(1);
        String csv = service.exportCsv();
        assertThat(csv).startsWith("order_token,");
        assertThat(csv).contains(o.getOrderToken());
    }
}

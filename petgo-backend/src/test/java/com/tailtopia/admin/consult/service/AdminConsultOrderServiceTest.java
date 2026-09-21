package com.tailtopia.admin.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.consult.dto.AdminConsultOrderRow;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.domain.ConsultOrderVerifyStatus;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultOrderStageEventRepository;
import com.tailtopia.pay.domain.PayChannel;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Sort;

/** L0（Story 9.3）：展示态派生（含 refund_rejected）+ CSV 组装/转义 + 标记审计。纯 Mockito。 */
class AdminConsultOrderServiceTest {

    private ConsultOrderRepository orders;
    private ConsultOrderStageEventRepository stageEvents;
    private AdminAuditService audit;
    private AdminConsultOrderService svc;

    @BeforeEach
    void setUp() {
        orders = Mockito.mock(ConsultOrderRepository.class);
        stageEvents = Mockito.mock(ConsultOrderStageEventRepository.class);
        audit = Mockito.mock(AdminAuditService.class);
        // V1.3.0 Story 8.4：新增 Messages 入参（导出表头随 locale）。
        svc = new AdminConsultOrderService(orders, stageEvents, audit,
                com.tailtopia.support.TestMessages.real());
    }

    private long idSeq = 0;

    private ConsultOrder order(String token, ConsultOrderStatus status, boolean refundRejected) {
        ConsultOrder o = ConsultOrder.inProgress(token, 100L, 9L, 3L, 50000L, PayChannel.QRIS, null,
                30000L, 60, 50000L, Instant.now());
        set(o, "id", ++idSeq); // bug 313 后 toRow 组 displayNo 需 id+createdAt（非持久化单测须手工设）
        set(o, "createdAt", Instant.now());
        set(o, "status", status);
        set(o, "refundRejected", refundRejected);
        return o;
    }

    @Test
    void statusCodeDerivesRefundRejected() {
        when(orders.findAll(Mockito.any(Sort.class))).thenReturn(List.of(
                order("t1", ConsultOrderStatus.IN_PROGRESS, false),
                order("t2", ConsultOrderStatus.COMPLETED, false),
                order("t3", ConsultOrderStatus.COMPLETED, true),
                order("t4", ConsultOrderStatus.REFUNDING, false),
                order("t5", ConsultOrderStatus.REFUNDED, false)));

        List<AdminConsultOrderRow> rows = svc.list();

        assertThat(rows).extracting(AdminConsultOrderRow::statusCode).containsExactly(
                "IN_PROGRESS", "COMPLETED", "COMPLETED_REFUND_REJECTED", "REFUNDING", "REFUNDED");
    }

    @Test
    void exportCsvHasHeaderAndEscapes() {
        ConsultOrder o = order("tok,quote\"x", ConsultOrderStatus.COMPLETED, false);
        when(orders.findAll(Mockito.any(Sort.class))).thenReturn(List.of(o));

        String csv = svc.exportCsv();

        // V1.3.0 Story 8.4：表头改成**随 locale 的文案**（走 AdminExportWriter），
        // 所以断言的是那几个 message 的值而不是写死的字面量。
        assertThat(csv).startsWith(
                com.tailtopia.support.TestMessages.real().get("admin.v130.consultOrders.export.orderToken") + ",");
        // 含逗号/引号字段被包裹 + 引号翻倍（RFC 4180，由写入器统一处理）。
        assertThat(csv).contains("\"tok,quote\"\"x\"");
        assertThat(csv).contains(",50000,30000,COMPLETED,0,,");
        // 🔴 行尾必须是 CRLF：写错的话 Excel 会把整份文件读成一行。
        assertThat(csv).contains("\r\n");
    }

    @Test
    void markVerifyPersistsAndAudits() {
        ConsultOrder o = order("tok", ConsultOrderStatus.COMPLETED, false);
        when(orders.findByOrderToken("tok")).thenReturn(Optional.of(o));

        svc.markVerify("tok", ConsultOrderVerifyStatus.TO_VERIFY, "查一下", 7L);

        assertThat(o.getAdminVerifyStatus()).isEqualTo(ConsultOrderVerifyStatus.TO_VERIFY);
        assertThat(o.getAdminVerifyNote()).isEqualTo("查一下");
        // 标记不改订单业务状态。
        assertThat(o.getStatus()).isEqualTo(ConsultOrderStatus.COMPLETED);
        verify(orders).save(o);
        verify(audit).record(eq(7L), eq("CONSULT_ORDER_VERIFY"), anyString(), eq("tok"), anyString());
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

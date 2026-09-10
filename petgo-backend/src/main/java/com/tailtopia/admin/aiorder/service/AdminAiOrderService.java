package com.tailtopia.admin.aiorder.service;

import com.tailtopia.admin.aiorder.dto.AdminAiOrderDetail;
import com.tailtopia.admin.aiorder.dto.AdminAiOrderRow;
import com.tailtopia.admin.aiorder.dto.AiRevenueSummary;
import com.tailtopia.admin.shared.export.AdminExportWriter;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.triage.domain.AiConsultOrder;
import com.tailtopia.triage.domain.AiConsultOrderStatus;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台 AI 问诊收入统计 + 订单只读查询（Story 9.4，AB-8C/8G）。与兽医咨询订单（{@code consult_orders}，9-3）
 * **命名空间隔离**：独立表 {@code ai_consult_orders}、独立页面。<b>收入口径 = COMPLETED 金额之和</b>
 * （PENDING 不计、ABNORMAL 供对账不计）。AI 一次性解锁，无退款/分成/待核查。
 */
@Service
public class AdminAiOrderService {

    private final AiConsultOrderRepository orders;
    /** 导出表头随当前 locale（V1.3.0 Story 8.4 走 AdminExportWriter 之后）。 */
    private final com.tailtopia.shared.i18n.Messages msg;

    public AdminAiOrderService(AiConsultOrderRepository orders,
            com.tailtopia.shared.i18n.Messages msg) {
        this.orders = orders;
        this.msg = msg;
    }

    @Transactional(readOnly = true)
    public AiRevenueSummary summary() {
        long revenue = orders.sumAmountByStatus(AiConsultOrderStatus.COMPLETED);
        long qris = orders.sumAmountByStatusAndChannel(AiConsultOrderStatus.COMPLETED, PayChannel.QRIS);
        long pawcoin = orders.sumAmountByStatusAndChannel(
                AiConsultOrderStatus.COMPLETED, PayChannel.PAWCOIN);
        return new AiRevenueSummary(revenue,
                orders.countByStatus(AiConsultOrderStatus.COMPLETED),
                orders.countByStatus(AiConsultOrderStatus.PENDING_PAYMENT),
                orders.countByStatus(AiConsultOrderStatus.ABNORMAL),
                qris, pawcoin);
    }

    @Transactional(readOnly = true)
    public List<AdminAiOrderRow> list() {
        return orders.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(AdminAiOrderService::toRow).toList();
    }

    @Transactional(readOnly = true)
    public AdminAiOrderDetail detail(String orderToken) {
        AiConsultOrder o = orders.findByOrderToken(orderToken)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
        return new AdminAiOrderDetail(o.getOrderToken(), o.getUserId(), o.getTriageTaskId(),
                o.getAmount(), o.getPayChannel().name(), o.getPaymentIntentToken(),
                o.getStatus().name(), o.getPaidAt(), o.getCreatedAt());
    }

    /**
     * CSV 导出。⚠️ <b>永远导出全表</b>（本端点不接收筛选参数，页面按钮因此写「导出全部」）。
     *
     * <p>V1.3.0 Story 8.4：改经 {@link AdminExportWriter#csv}（AD-10 规则 12）——
     * RFC 4180 转义 + 公式注入防护 + 表头随会话 locale。
     */
    @Transactional(readOnly = true)
    public String exportCsv() {
        List<String> headers = List.of(
                msg.get("admin.v130.aiOrders.export.orderToken"),
                msg.get("admin.v130.aiOrders.export.userId"),
                msg.get("admin.v130.aiOrders.export.triageTaskId"),
                msg.get("admin.v130.aiOrders.export.amount"),
                msg.get("admin.v130.aiOrders.export.payChannel"),
                msg.get("admin.v130.aiOrders.export.status"),
                msg.get("admin.v130.aiOrders.export.paidAt"),
                msg.get("admin.v130.aiOrders.export.createdAt"));
        List<List<Object>> rows = list().stream().map(r -> List.<Object>of(
                        r.orderToken(),
                        r.userId(),
                        r.triageTaskId(),
                        r.amount(),
                        r.payChannel(),
                        r.status(),
                        r.paidAt() == null ? "" : r.paidAt(),
                        r.createdAt() == null ? "" : r.createdAt()))
                .toList();
        return AdminExportWriter.csv(headers, rows);
    }

    private static AdminAiOrderRow toRow(AiConsultOrder o) {
        return new AdminAiOrderRow(o.getOrderToken(),
                com.tailtopia.order.dto.OrderDisplayNo.of(com.tailtopia.order.dto.OrderDisplayNo.AI_UNLOCK, o.getId(), o.getCreatedAt()),
                o.getUserId(), o.getTriageTaskId(),
                o.getAmount(), o.getPayChannel().name(), o.getStatus().name(),
                o.getPaidAt(), o.getCreatedAt());
    }
}

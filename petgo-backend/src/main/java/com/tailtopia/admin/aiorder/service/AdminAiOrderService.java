package com.tailtopia.admin.aiorder.service;

import com.tailtopia.admin.aiorder.dto.AdminAiOrderDetail;
import com.tailtopia.admin.aiorder.dto.AdminAiOrderRow;
import com.tailtopia.admin.aiorder.dto.AiRevenueSummary;
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

    public AdminAiOrderService(AiConsultOrderRepository orders) {
        this.orders = orders;
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
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        return new AdminAiOrderDetail(o.getOrderToken(), o.getUserId(), o.getTriageTaskId(),
                o.getAmount(), o.getPayChannel().name(), o.getPaymentIntentToken(),
                o.getStatus().name(), o.getPaidAt(), o.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public String exportCsv() {
        StringBuilder sb = new StringBuilder(
                "order_token,user_id,triage_task_id,amount,pay_channel,status,paid_at,created_at\n");
        for (AdminAiOrderRow r : list()) {
            sb.append(csv(r.orderToken())).append(',')
                    .append(r.userId()).append(',')
                    .append(r.triageTaskId()).append(',')
                    .append(r.amount()).append(',')
                    .append(csv(r.payChannel())).append(',')
                    .append(csv(r.status())).append(',')
                    .append(r.paidAt() == null ? "" : r.paidAt()).append(',')
                    .append(r.createdAt() == null ? "" : r.createdAt()).append('\n');
        }
        return sb.toString();
    }

    private static AdminAiOrderRow toRow(AiConsultOrder o) {
        return new AdminAiOrderRow(o.getOrderToken(),
                com.tailtopia.order.dto.OrderDisplayNo.of(com.tailtopia.order.dto.OrderDisplayNo.AI_UNLOCK, o.getId(), o.getCreatedAt()),
                o.getUserId(), o.getTriageTaskId(),
                o.getAmount(), o.getPayChannel().name(), o.getStatus().name(),
                o.getPaidAt(), o.getCreatedAt());
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}

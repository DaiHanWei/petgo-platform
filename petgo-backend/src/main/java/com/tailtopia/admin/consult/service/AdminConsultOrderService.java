package com.tailtopia.admin.consult.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.consult.dto.AdminConsultOrderDetail;
import com.tailtopia.admin.consult.dto.AdminConsultOrderRow;
import com.tailtopia.admin.consult.dto.AdminConsultOrderStageRow;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderVerifyStatus;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultOrderStageEventRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台兽医咨询订单**只读管理**（Story 9.3，AB-8B）。列表 / 详情（成交快照 + 阶段时间线）/ 待核查标记 / CSV 导出。
 * <b>无退款入口</b>——退款只走客服工单两段审批（4-3/4-6）。展示态复用用户订单中心同款派生（vetStatusCode）。
 * 标记是纯人工注记（不改订单业务状态、不触发退款/冻结，AB-7A「无自动拦截」），接 {@link AdminAuditService} 审计。
 */
@Service
public class AdminConsultOrderService {

    private final ConsultOrderRepository orders;
    private final ConsultOrderStageEventRepository stageEvents;
    private final AdminAuditService audit;

    public AdminConsultOrderService(ConsultOrderRepository orders,
            ConsultOrderStageEventRepository stageEvents, AdminAuditService audit) {
        this.orders = orders;
        this.stageEvents = stageEvents;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AdminConsultOrderRow> list() {
        return orders.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(AdminConsultOrderService::toRow).toList();
    }

    @Transactional(readOnly = true)
    public AdminConsultOrderDetail detail(String orderToken) {
        ConsultOrder o = orders.findByOrderToken(orderToken)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        List<AdminConsultOrderStageRow> stages = stageEvents
                .findByConsultOrderIdOrderByOccurredAtAsc(o.getId()).stream()
                .map(e -> new AdminConsultOrderStageRow(
                        e.getEventType().name(), e.getOccurredAt(), e.getNote()))
                .toList();
        return new AdminConsultOrderDetail(
                o.getOrderToken(), o.getUserId(), o.getVetId(), o.getPetProfileId(), o.getAmount(),
                o.getPayChannel() == null ? null : o.getPayChannel().name(), o.getVetPayout(),
                o.getVetShareRateSnapshot(), o.getUnitPriceSnapshot(), statusCode(o), o.isRefundRejected(),
                o.getRebroadcastCount(), verifyStr(o.getAdminVerifyStatus()), o.getAdminVerifyNote(),
                o.getSessionStartedAt(), o.getSessionEndedAt(), o.getPaidAt(), o.getCreatedAt(), stages);
    }

    /** 标记待核查/已核查（纯注记 + 审计）。传 null 状态 → 清除标记。 */
    @Transactional
    public void markVerify(String orderToken, ConsultOrderVerifyStatus status, String note, long adminId) {
        ConsultOrder o = orders.findByOrderToken(orderToken)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        o.applyVerify(status, note, adminId);
        orders.save(o);
        audit.record(adminId, "CONSULT_ORDER_VERIFY", "consult_order", orderToken,
                "verify=" + (status == null ? "CLEARED" : status.name()));
    }

    /** CSV 导出（订单号/用户/兽医/金额/分成/状态/重播/待核查/时间）。 */
    @Transactional(readOnly = true)
    public String exportCsv() {
        StringBuilder sb = new StringBuilder(
                "order_token,user_id,vet_id,amount,vet_payout,status,rebroadcast_count,verify_status,paid_at,created_at\n");
        for (AdminConsultOrderRow r : list()) {
            sb.append(csv(r.orderToken())).append(',')
                    .append(r.userId()).append(',')
                    .append(r.vetId()).append(',')
                    .append(r.amount()).append(',')
                    .append(r.vetPayout() == null ? "" : r.vetPayout()).append(',')
                    .append(csv(r.statusCode())).append(',')
                    .append(r.rebroadcastCount()).append(',')
                    .append(csv(r.verifyStatus())).append(',')
                    .append(r.paidAt() == null ? "" : r.paidAt()).append(',')
                    .append(r.createdAt() == null ? "" : r.createdAt()).append('\n');
        }
        return sb.toString();
    }

    private static AdminConsultOrderRow toRow(ConsultOrder o) {
        return new AdminConsultOrderRow(o.getOrderToken(),
                com.tailtopia.order.dto.OrderDisplayNo.of(com.tailtopia.order.dto.OrderDisplayNo.VET_CONSULT, o.getId(), o.getCreatedAt()),
                o.getUserId(), o.getVetId(), o.getAmount(),
                o.getVetPayout(), statusCode(o), o.getRebroadcastCount(),
                verifyStr(o.getAdminVerifyStatus()), o.getPaidAt(), o.getCreatedAt());
    }

    /** 展示态派生（与用户订单中心 vetStatusCode 一致）：IN_PROGRESS / COMPLETED[_REFUND_REJECTED] / REFUNDING / REFUNDED。 */
    private static String statusCode(ConsultOrder o) {
        return switch (o.getStatus()) {
            case IN_PROGRESS -> "IN_PROGRESS";
            case COMPLETED -> o.isRefundRejected() ? "COMPLETED_REFUND_REJECTED" : "COMPLETED";
            case REFUNDING -> "REFUNDING";
            case REFUNDED -> "REFUNDED";
        };
    }

    private static String verifyStr(ConsultOrderVerifyStatus s) {
        return s == null ? "" : s.name();
    }

    /** CSV 字段转义（逗号/引号/换行 → 双引号包裹 + 引号翻倍）。 */
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

package com.tailtopia.admin.consult.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.consult.dto.AdminConsultOrderDetail;
import com.tailtopia.admin.consult.dto.AdminConsultOrderRow;
import com.tailtopia.admin.consult.dto.AdminConsultOrderStageRow;
import com.tailtopia.admin.consult.dto.ConsultOrderSummary;
import com.tailtopia.admin.shared.export.AdminExportWriter;
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
    /** 导出表头随当前 locale（V1.3.0 Story 8.4 走 AdminExportWriter 之后）。 */
    private final com.tailtopia.shared.i18n.Messages msg;

    public AdminConsultOrderService(ConsultOrderRepository orders,
            ConsultOrderStageEventRepository stageEvents, AdminAuditService audit,
            com.tailtopia.shared.i18n.Messages msg) {
        this.orders = orders;
        this.stageEvents = stageEvents;
        this.audit = audit;
        this.msg = msg;
    }

    /**
     * 摘要条三格（Story 8.4 · AC1）。入参就是列表那一份 rows，不另查一遍。
     *
     * <p>⚠️ 成交额算的是**全部单**的金额之和，与列表口径一致；不按状态过滤 ——
     * 过滤了的话「成交额 ÷ 单数」得不到列表上看得到的那个均价，运营对不上账。
     */
    public ConsultOrderSummary summary(List<AdminConsultOrderRow> rows) {
        return new ConsultOrderSummary(
                rows.stream().mapToLong(AdminConsultOrderRow::amount).sum(),
                rows.size(),
                rows.stream().filter(r -> "TO_VERIFY".equals(r.verifyStatus())).count());
    }

    /** 单行（抽屉里标记成功后 oob 换掉列表里的那一行，Story 8.4）。 */
    @Transactional(readOnly = true)
    public AdminConsultOrderRow row(String orderToken) {
        return toRow(orders.findByOrderToken(orderToken)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound")));
    }

    @Transactional(readOnly = true)
    public List<AdminConsultOrderRow> list() {
        return orders.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(AdminConsultOrderService::toRow).toList();
    }

    @Transactional(readOnly = true)
    public AdminConsultOrderDetail detail(String orderToken) {
        ConsultOrder o = orders.findByOrderToken(orderToken)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
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
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
        o.applyVerify(status, note, adminId);
        orders.save(o);
        audit.record(adminId, "CONSULT_ORDER_VERIFY", "consult_order", orderToken,
                "verify=" + (status == null ? "CLEARED" : status.name()));
    }

    /**
     * CSV 导出（订单号/用户/兽医/金额/分成/状态/重播/待核查/时间）。
     *
     * <p>⚠️ <b>永远导出全表</b>：本端点不接收任何筛选参数（页面上的按钮因此写「导出全部」）。
     *
     * <p>V1.3.0 Story 8.4：改经 {@link AdminExportWriter#csv}（AD-10 规则 12，全站导出一个出口）——
     * RFC 4180 转义 + 前导 {@code = + - @} 的公式注入防护 + 表头随会话 locale。
     * 原先是各自手拼字符串、各自处理引号：同一个转义规则在仓库里有两份实现，
     * 而其中一份漏掉了 CR（{@code \r}）。
     */
    @Transactional(readOnly = true)
    public String exportCsv() {
        List<String> headers = List.of(
                msg.get("admin.v130.consultOrders.export.orderToken"),
                msg.get("admin.v130.consultOrders.export.userId"),
                msg.get("admin.v130.consultOrders.export.vetId"),
                msg.get("admin.v130.consultOrders.export.amount"),
                msg.get("admin.v130.consultOrders.export.vetPayout"),
                msg.get("admin.v130.consultOrders.export.status"),
                msg.get("admin.v130.consultOrders.export.rebroadcast"),
                msg.get("admin.v130.consultOrders.export.verify"),
                msg.get("admin.v130.consultOrders.export.paidAt"),
                msg.get("admin.v130.consultOrders.export.createdAt"));
        List<List<Object>> rows = list().stream().map(r -> List.<Object>of(
                        r.orderToken(),
                        r.userId(),
                        r.vetId(),
                        r.amount(),
                        r.vetPayout() == null ? "" : r.vetPayout(),
                        r.statusCode(),
                        r.rebroadcastCount(),
                        r.verifyStatus(),
                        r.paidAt() == null ? "" : r.paidAt(),
                        r.createdAt() == null ? "" : r.createdAt()))
                .toList();
        return AdminExportWriter.csv(headers, rows);
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

}

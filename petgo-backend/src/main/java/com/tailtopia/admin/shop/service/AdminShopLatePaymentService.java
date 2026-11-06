package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.ShopOrderRefund;
import com.tailtopia.shop.order.refund.ShopOrderRefundRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.LatePaymentService;
import com.tailtopia.shop.order.service.ReorderChecklist;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单抽屉「取消后到账」一节（V1.4.2 shop-admin Story 7.2 · AD-11 · F2 ~ F6）。只读组装；处置走 {@link LatePaymentService}。
 *
 * <ul>
 *   <li>F2：标记、到账金额 / 时间、取消时间；重复付款单（订单未取消）只给「退还到账款」（O-19）；</li>
 *   <li>F3 / F4：核对清单系统项（{@link LatePaymentService#checklist}）；</li>
 *   <li>F5：已重新下单 → 新单链接、操作人（取审计 {@code SHOP_ORDER_LATE_PAYMENT_REORDERED}）、时间（新单 {@code created_at}）；</li>
 *   <li>已退还 → 操作人 / 时间取 {@code LATE_PAYMENT} 退款单的 {@code created_by_admin_id} / {@code created_at}。</li>
 * </ul>
 */
@Service
public class AdminShopLatePaymentService {

    private final LatePaymentService latePayments;
    private final ShopOrderRepository orders;
    private final ShopOrderRefundRepository refunds;
    private final AdminAuditLogRepository auditLogs;
    private final AdminAccountRepository adminAccounts;

    public AdminShopLatePaymentService(LatePaymentService latePayments, ShopOrderRepository orders,
            ShopOrderRefundRepository refunds, AdminAuditLogRepository auditLogs, AdminAccountRepository adminAccounts) {
        this.latePayments = latePayments;
        this.orders = orders;
        this.refunds = refunds;
        this.auditLogs = auditLogs;
        this.adminAccounts = adminAccounts;
    }

    /**
     * @param duplicate 重复付款（订单未取消）：只能退还到账款
     * @param checklist 可重新下单时的系统项；不可重新下单（重复付款 / 已处理 / 注销用户）为 null
     */
    public record LateView(long amount, Instant paidAt, Instant cancelledAt, boolean duplicate, String resolution,
            ReorderChecklist.Result checklist, String newOrderToken, String newOrderNo, String newOrderStatus,
            String operator, Instant handledAt) {

        public boolean pending() {
            return resolution == null;
        }
    }

    @Transactional(readOnly = true)
    public LateView of(ShopOrder o) {
        if (o.getLatePaidAmount() == null) {
            return null;
        }
        boolean duplicate = o.getStatus() != ShopOrderStatus.CANCELLED;
        String resolution = o.getLatePaymentResolution();
        if (resolution == null) {
            return new LateView(o.getLatePaidAmount(), o.getLatePaidAt(), o.getCancelledAt(), duplicate, null,
                    duplicate ? null : latePayments.checklist(o), null, null, null, null, null);
        }
        if ("REORDERED".equals(resolution)) {
            ShopOrder n = orders.findByReorderOfOrderId(o.getId()).orElse(null);
            String operator = auditLogs.findByActionTypeAndTargetIdIn(AuditActions.SHOP_ORDER_LATE_PAYMENT_REORDERED,
                            List.of(o.getPublicToken())).stream()
                    .max(Comparator.comparing(AdminAuditLog::getCreatedAt))
                    .map(AdminAuditLog::getActorAccountId).map(this::nameOf).orElse(null);
            return new LateView(o.getLatePaidAmount(), o.getLatePaidAt(), o.getCancelledAt(), duplicate, resolution,
                    null, n == null ? null : n.getPublicToken(), n == null ? null : n.getDisplayNo(),
                    n == null ? null : n.getStatus().name(), operator, n == null ? null : n.getCreatedAt());
        }
        ShopOrderRefund r = refunds.findByOrderIdOrderByCreatedAtAscIdAsc(o.getId()).stream()
                .filter(x -> x.getReason() == CancelReason.LATE_PAYMENT).reduce((a, b) -> b).orElse(null);
        return new LateView(o.getLatePaidAmount(), o.getLatePaidAt(), o.getCancelledAt(), duplicate, resolution,
                null, null, null, null, r == null ? null : nameOf(r.getCreatedByAdminId()),
                r == null ? null : r.getCreatedAt());
    }

    private String nameOf(Long adminId) {
        return adminId == null ? null : adminAccounts.findById(adminId).map(a -> a.getDisplayName()).orElse(null);
    }
}

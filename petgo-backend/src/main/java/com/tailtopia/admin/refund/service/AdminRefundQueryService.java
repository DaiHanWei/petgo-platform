package com.tailtopia.admin.refund.service;

import com.tailtopia.admin.refund.dto.AdminRefundView;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.refund.domain.RefundRequest;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台退款查询（Story 4.6，供退款管理列表/详情 SSR）。**PII 脱敏**：收款账号仅末 4 位，户名不回显。
 * 出款全量 PII 只在 {@code RefundService.payoutRefund} 内解密进网关，绝不进 UI/日志。
 */
@Service
public class AdminRefundQueryService {

    private final RefundRequestRepository refunds;
    private final ConsultOrderRepository orders;

    public AdminRefundQueryService(RefundRequestRepository refunds, ConsultOrderRepository orders) {
        this.refunds = refunds;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public List<AdminRefundView> list() {
        return refunds.findAllByOrderByCreatedAtDesc().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public AdminRefundView find(String refundToken) {
        RefundRequest r = refunds.findByRefundToken(refundToken)
                .orElseThrow(() -> AppException.notFound("退款请求不存在"));
        return toView(r);
    }

    private AdminRefundView toView(RefundRequest r) {
        ConsultOrder order = orders.findById(r.getOrderId()).orElse(null);
        return new AdminRefundView(
                r.getRefundToken(),
                order == null ? null : order.getOrderToken(),
                order == null ? null : order.getPayChannel().name(),
                r.getNeedDecision().name(),
                r.getApprovalStatus() == null ? null : r.getApprovalStatus().name(),
                r.getOrderAmount(),
                r.getNetAmount(),
                r.getPayoutChannel() == null ? null : r.getPayoutChannel().name(),
                maskAccount(r.getPayoutAccount()),
                r.getApprovalNote(),
                r.getRejectReason(),
                r.getPaymentProof());
    }

    /** 脱敏：仅保留末 4 位（PII 红线，全账号绝不进 UI）。 */
    private static String maskAccount(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        int n = account.length();
        return n <= 4 ? "****" : "****" + account.substring(n - 4);
    }
}

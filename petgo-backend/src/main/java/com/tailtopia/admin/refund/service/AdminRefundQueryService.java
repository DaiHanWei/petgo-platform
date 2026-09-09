package com.tailtopia.admin.refund.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.refund.dto.AdminRefundView;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.refund.domain.ApprovalStatus;
import com.tailtopia.pay.refund.domain.NeedDecision;
import com.tailtopia.pay.refund.domain.RefundRequest;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台退款查询（Story 4.6，供退款管理列表/详情 SSR）。**PII 脱敏**：收款账号仅末 4 位，户名不回显。
 * 出款全量 PII 只在 {@code RefundService.payoutRefund} 内解密进网关，绝不进 UI/日志。
 *
 * <p>V1.3.0 Story 2.8：A6 三段流工作台的只读装配——四页签（待客服判定 / 待主管审批 / 待财务打款 / 已完结·已驳回）
 * 分页与计数、处置后「下一条」、三段操作人显示名。<b>不碰 {@code RefundService}</b>（职责分离护栏在服务层）。
 */
@Service
public class AdminRefundQueryService {

    /** 三段流页签。 */
    public enum Stage {
        SUBMIT, APPROVE, PAYOUT, CLOSED;

        public static Stage of(String raw) {
            if (raw == null || raw.isBlank()) {
                return SUBMIT;
            }
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return SUBMIT;
            }
        }

        public String param() {
            return name().toLowerCase();
        }
    }

    private static final Set<ApprovalStatus> PAYOUT_STATUSES = EnumSet.of(ApprovalStatus.APPROVED, ApprovalStatus.PROCESSING);

    private final RefundRequestRepository refunds;
    private final ConsultOrderRepository orders;
    private final FeedbackTicketRepository tickets;
    private final AdminAccountRepository adminAccounts;

    public AdminRefundQueryService(RefundRequestRepository refunds, ConsultOrderRepository orders,
            FeedbackTicketRepository tickets, AdminAccountRepository adminAccounts) {
        this.refunds = refunds;
        this.orders = orders;
        this.tickets = tickets;
        this.adminAccounts = adminAccounts;
    }

    @Transactional(readOnly = true)
    public AdminRefundView find(String refundToken) {
        RefundRequest r = refunds.findByRefundToken(refundToken)
                .orElseThrow(() -> AppException.notFound("退款请求不存在").code("admin.err.refund.notFound"));
        return toView(r, operatorNames(List.of(r.getSubmitterAdminId(), r.getApproverAdminId(), r.getPayerAdminId())));
    }

    /** 工作台左栏一页（按段；判定 / 审批 / 打款段先进先出，已完结最新在前）。 */
    @Transactional(readOnly = true)
    public Page<AdminRefundView> page(Stage stage, Pageable pageable) {
        Page<RefundRequest> page = switch (stage) {
            case SUBMIT -> refunds.findByNeedDecisionOrderByCreatedAtAsc(NeedDecision.PENDING, pageable);
            case APPROVE -> refunds.findApprovalStage(pageable);
            case PAYOUT -> refunds.findByApprovalStatusInOrderByCreatedAtAsc(PAYOUT_STATUSES, pageable);
            case CLOSED -> refunds.findClosedStage(pageable);
        };
        return page.map(r -> toView(r, Map.of()));
    }

    /** 四段计数。 */
    @Transactional(readOnly = true)
    public Map<String, Long> counts() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("submit", refunds.countByNeedDecision(NeedDecision.PENDING));
        out.put("approve", refunds.countApprovalStage());
        out.put("payout", refunds.countByApprovalStatusIn(PAYOUT_STATUSES));
        out.put("closed", refunds.countClosedStage());
        return out;
    }

    /** 待处理数 = 判定 + 审批 + 打款三段之和（与 {@link #counts()} 同一组查询，不含已完结），供侧栏角标（Story 2.9 AC1）。 */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return refunds.countByNeedDecision(NeedDecision.PENDING) + refunds.countApprovalStage()
                + refunds.countByApprovalStatusIn(PAYOUT_STATUSES);
    }

    /** 某段的第一条（处置后 = 下一条）token；无则空串。 */
    @Transactional(readOnly = true)
    public String nextToken(Stage stage) {
        Page<AdminRefundView> first = page(stage, PageRequest.of(0, 1));
        return first.isEmpty() ? "" : first.getContent().get(0).refundToken();
    }

    private AdminRefundView toView(RefundRequest r, Map<Long, String> names) {
        ConsultOrder order = orders.findById(r.getOrderId()).orElse(null);
        // 来源工单溯源（bug 20260728-384）：主管/财务审批时可回看客服判定依据。
        String sourceTicketToken = r.getRelatedTicketId() == null ? null
                : tickets.findById(r.getRelatedTicketId()).map(FeedbackTicket::getTicketToken).orElse(null);
        String needDecision = r.getNeedDecision().name();
        String approvalStatus = r.getApprovalStatus() == null ? null : r.getApprovalStatus().name();
        return new AdminRefundView(
                r.getRefundToken(),
                order == null ? null : order.getOrderToken(),
                order == null ? null : order.getPayChannel().name(),
                needDecision,
                approvalStatus,
                r.getOrderAmount(),
                r.getNetAmount(),
                r.getPayoutChannel() == null ? null : r.getPayoutChannel().name(),
                maskAccount(r.getPayoutAccount()),
                r.getApprovalNote(),
                r.getRejectReason(),
                r.getPaymentProof(),
                sourceTicketToken,
                AdminRefundView.stageOf(needDecision, approvalStatus),
                r.getChannelFee(),
                nameOf(names, r.getSubmitterAdminId()),
                nameOf(names, r.getApproverAdminId()),
                nameOf(names, r.getPayerAdminId()),
                r.getCreatedAt(),
                r.getApprovedAt(),
                r.getRejectedAt(),
                r.getPaidAt(),
                r.getPayoutProofKey());
    }

    private static String nameOf(Map<Long, String> names, Long id) {
        return id == null ? null : names.getOrDefault(id, "#" + id);
    }

    private Map<Long, String> operatorNames(List<Long> ids) {
        Set<Long> distinct = new HashSet<>();
        ids.stream().filter(Objects::nonNull).forEach(distinct::add);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>();
        adminAccounts.findAllById(distinct).forEach(a -> out.put(a.getId(), a.getDisplayName()));
        return out;
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

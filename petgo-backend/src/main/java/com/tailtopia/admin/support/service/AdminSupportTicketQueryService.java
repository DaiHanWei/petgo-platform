package com.tailtopia.admin.support.service;

import com.tailtopia.admin.support.dto.AdminTicketView;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.refund.domain.RefundRequest;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.domain.TicketStatus;
import com.tailtopia.support.domain.TicketAttachment;
import com.tailtopia.support.domain.TicketLabel;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import com.tailtopia.support.repository.TicketAttachmentRepository;
import com.tailtopia.support.repository.TicketLabelRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台客服工单查询（Story 4.7，供工单管理列表/详情 SSR）。
 *
 * <p>可见性分层（bug440 权限对齐后 {@code support.view} 也可进列表/详情，PR#34 finding #13）：
 * 联系方式原文（PII）仅 {@code support.handle}/SUPER_ADMIN 可见，view-only 看到脱敏值；
 * 正文/附件签名 URL 维持详情可见（处理工单的最小必要信息）。
 *
 * <p>列表页只渲染 token/标题/状态/联系标记/CSAT——标签/附件/订单/退款查询全部裁掉（finding #7 N+1），
 * 详情页再查。详情附带：附件签名 URL（仅详情签发，短 TTL 不缓存不入库，bug 20260728-387）、
 * 关联订单 token 与该订单退款单状态（AB-5B 客服判定前置，bug 20260728-384）。
 */
@Service
public class AdminSupportTicketQueryService {

    private final FeedbackTicketRepository tickets;
    private final TicketLabelRepository labels;
    private final TicketAttachmentRepository attachments;
    private final ConsultOrderRepository orders;
    private final RefundRequestRepository refunds;
    private final SignedUrlService signedUrls;

    public AdminSupportTicketQueryService(FeedbackTicketRepository tickets, TicketLabelRepository labels,
            TicketAttachmentRepository attachments, ConsultOrderRepository orders,
            RefundRequestRepository refunds, SignedUrlService signedUrls) {
        this.tickets = tickets;
        this.labels = labels;
        this.attachments = attachments;
        this.orders = orders;
        this.refunds = refunds;
        this.signedUrls = signedUrls;
    }

    /** 列表页轻量视图：只填列表实际渲染的字段，不发标签/附件/订单/退款查询（finding #7）。 */
    @Transactional(readOnly = true)
    public Page<AdminTicketView> list(Pageable pageable) {
        return tickets.findAll(pageable).map(t -> new AdminTicketView(
                t.getTicketToken(), t.getSubject(), null,
                t.getContactType().name(), null,
                t.isNeedContactCustomer(), t.isContactedCustomer(), t.getStatus().name(),
                List.of(), 0, List.of(), null, null, null,
                t.getCsatScore(), null, t.getCreatedAt(), t.getResolvedAt(), null, null, null, null));
    }

    /** A5 工作台三态（V1.3.0 Story 2.7 AC1）：待处理 / 待联系（需联系且未联系）/ 已结案。 */
    public enum State {
        PENDING, CONTACT, CLOSED;

        public static State of(String raw) {
            if (raw == null || raw.isBlank()) {
                return PENDING;
            }
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return PENDING;
            }
        }

        public String param() {
            return name().toLowerCase();
        }
    }

    private static final java.util.Set<TicketStatus> OPEN_STATUSES =
            java.util.EnumSet.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS);
    private static final java.util.Set<TicketStatus> CLOSED_STATUSES =
            java.util.EnumSet.of(TicketStatus.RESOLVED, TicketStatus.CLOSED);

    /** 工作台左栏一页（轻量视图，时间倒序）。 */
    @Transactional(readOnly = true)
    public Page<AdminTicketView> page(State state, Pageable pageable) {
        Page<FeedbackTicket> page = switch (state) {
            case CONTACT -> tickets.findByStatusInAndNeedContactCustomerTrueAndContactedCustomerFalseOrderByCreatedAtDesc(
                    OPEN_STATUSES, pageable);
            case CLOSED -> tickets.findByStatusInOrderByCreatedAtDesc(CLOSED_STATUSES, pageable);
            default -> tickets.findByStatusInOrderByCreatedAtDesc(OPEN_STATUSES, pageable);
        };
        return page.map(t -> new AdminTicketView(
                t.getTicketToken(), t.getSubject(), null,
                t.getContactType().name(), null,
                t.isNeedContactCustomer(), t.isContactedCustomer(), t.getStatus().name(),
                List.of(), 0, List.of(), null, null, null,
                t.getCsatScore(), null, t.getCreatedAt(), t.getResolvedAt(), null, null, null, null));
    }

    /** 三态计数。 */
    @Transactional(readOnly = true)
    public java.util.Map<String, Long> counts() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        out.put("pending", tickets.countByStatusIn(OPEN_STATUSES));
        out.put("contact", tickets.countByStatusInAndNeedContactCustomerTrueAndContactedCustomerFalse(OPEN_STATUSES));
        out.put("closed", tickets.countByStatusIn(CLOSED_STATUSES));
        return out;
    }

    /** 待处理数 = {@code counts().get("pending")} 同一查询，供侧栏角标（Story 2.9 AC1）。 */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return tickets.countByStatusIn(OPEN_STATUSES);
    }

    /** 结案后「下一条」= 当前页签最新一条 token（待联系页签只在需联系未联系单里取；已结案页签无下一条）；无则空串。 */
    @Transactional(readOnly = true)
    public String nextPendingToken(State state) {
        return switch (state == null ? State.PENDING : state) {
            case CONTACT -> tickets.findFirstByStatusInAndNeedContactCustomerTrueAndContactedCustomerFalseOrderByCreatedAtDesc(OPEN_STATUSES)
                    .map(FeedbackTicket::getTicketToken).orElse("");
            case CLOSED -> "";
            default -> tickets.findFirstByStatusInOrderByCreatedAtDesc(OPEN_STATUSES)
                    .map(FeedbackTicket::getTicketToken).orElse("");
        };
    }

    /** 一张工单当前所属页签（`?open=` 深链落页签用）。 */
    public State stateOf(AdminTicketView t) {
        if (!t.open()) {
            return State.CLOSED;
        }
        return t.needContact() && !t.contacted() ? State.CONTACT : State.PENDING;
    }

    /**
     * 详情视图。{@code includeContactPii=false}（仅 {@code support.view} 的只读授权）时
     * 联系方式返回脱敏值（finding #13：PII 最小可见面）。
     */
    @Transactional(readOnly = true)
    public AdminTicketView find(String ticketToken, boolean includeContactPii) {
        FeedbackTicket t = tickets.findByTicketToken(ticketToken)
                .orElseThrow(() -> AppException.notFound("工单不存在").code("admin.err.ticket.notFound"));
        return toDetailView(t, includeContactPii);
    }

    private AdminTicketView toDetailView(FeedbackTicket t, boolean includeContactPii) {
        List<String> labelNames = new ArrayList<>();
        for (TicketLabel l : labels.findByTicketIdOrderByIdAsc(t.getId())) {
            labelNames.add(l.getLabel().name());
        }
        List<TicketAttachment> atts = attachments.findByTicketIdOrderByIdAsc(t.getId());
        // 附件签名 URL 仅详情页签发（短 TTL，列表页无渲染点不白签）。
        List<String> attachmentUrls = List.of();
        if (!atts.isEmpty()) {
            attachmentUrls = signedUrls.signAll(atts.stream().map(TicketAttachment::getObjectKey).toList());
        }
        String relatedOrderToken = null;
        String refundToken = null;
        String refundNeedDecision = null;
        Long orderAmount = null;
        String orderStatus = null;
        java.time.Instant orderPaidAt = null;
        String refundRejectReason = null;
        if (t.getRelatedOrderId() != null) {
            ConsultOrder order = orders.findById(t.getRelatedOrderId()).orElse(null);
            if (order != null) {
                relatedOrderToken = order.getOrderToken();
                orderAmount = order.getAmount();
                orderStatus = order.getStatus() == null ? null : order.getStatus().name();
                orderPaidAt = order.getPaidAt();
            }
            RefundRequest refund = refunds.findByOrderId(t.getRelatedOrderId()).orElse(null);
            if (refund != null) {
                refundToken = refund.getRefundToken();
                refundNeedDecision = refund.getNeedDecision().name();
                refundRejectReason = refund.getRejectReason();
            }
        }
        String contactValue = includeContactPii ? t.getContactValue() : maskContact(t.getContactValue());
        return new AdminTicketView(
                t.getTicketToken(), t.getSubject(), t.getBody(),
                t.getContactType().name(), contactValue,
                t.isNeedContactCustomer(), t.isContactedCustomer(), t.getStatus().name(),
                labelNames, atts.size(), attachmentUrls,
                relatedOrderToken, refundToken, refundNeedDecision,
                t.getCsatScore(), t.getCsatComment(),
                t.getCreatedAt(), t.getResolvedAt(), orderAmount, orderStatus, orderPaidAt, refundRejectReason);
    }

    /** 联系方式脱敏：首 2 + 末 2 可见（长度不足 5 全遮）。EMAIL/WHATSAPP 通用，够客服核对不够外泄。 */
    static String maskContact(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String v = value.trim();
        if (v.length() < 5) {
            return "****";
        }
        return v.substring(0, 2) + "****" + v.substring(v.length() - 2);
    }
}

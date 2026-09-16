package com.tailtopia.admin.support.service;

import com.tailtopia.admin.support.dto.AdminTicketView;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.support.domain.RelatedOrderType;
import com.tailtopia.pay.refund.domain.RefundRequest;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.support.domain.FeedbackTicket;
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
    /** Story 3-2：电商工单的订单号从这里取（只读）。 */
    private final ShopOrderRepository shopOrders;
    private final RefundRequestRepository refunds;
    private final SignedUrlService signedUrls;

    public AdminSupportTicketQueryService(FeedbackTicketRepository tickets, TicketLabelRepository labels,
            TicketAttachmentRepository attachments, ConsultOrderRepository orders,
            ShopOrderRepository shopOrders,
            RefundRequestRepository refunds, SignedUrlService signedUrls) {
        this.tickets = tickets;
        this.labels = labels;
        this.attachments = attachments;
        this.orders = orders;
        this.shopOrders = shopOrders;
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
                // 列表页不渲染订单/退款，这三个位置照旧留空（finding #7：不为列表发额外查询）。
                List.of(), 0, List.of(), null, null, null, null,
                t.getCsatScore(), null, t.getCreatedAt(), t.getResolvedAt()));
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
        String relatedOrderType = t.getRelatedOrderType().name();
        if (t.getRelatedOrderId() != null) {
            // 🔴 **必须按类型分流**：两类订单 id 数值空间完全重叠，无条件当问诊单查
            //    会给电商工单展示一条不相干的问诊单号（串单的可见形态）。
            if (t.getRelatedOrderType() == RelatedOrderType.CONSULT) {
                relatedOrderToken = orders.findById(t.getRelatedOrderId())
                        .map(ConsultOrder::getOrderToken).orElse(null);
                // 退款单只对问诊单存在 —— 拿电商单 id 去查会命中同号问诊单的退款请求。
                RefundRequest refund = refunds.findByOrderId(t.getRelatedOrderId()).orElse(null);
                if (refund != null) {
                    refundToken = refund.getRefundToken();
                    refundNeedDecision = refund.getNeedDecision().name();
                }
            } else {
                // 🔴 Story 4-3 已切换：读库列 display_no，不再用旧算法算。
                //   运营在工单上看到的号，必须与用户报出来的、与订单中心显示的是同一个。
                relatedOrderToken = shopOrders.findById(t.getRelatedOrderId())
                        .map(ShopOrder::getDisplayNo)
                        .orElse(null);
            }
        }
        String contactValue = includeContactPii ? t.getContactValue() : maskContact(t.getContactValue());
        return new AdminTicketView(
                t.getTicketToken(), t.getSubject(), t.getBody(),
                t.getContactType().name(), contactValue,
                t.isNeedContactCustomer(), t.isContactedCustomer(), t.getStatus().name(),
                labelNames, atts.size(), attachmentUrls,
                relatedOrderToken, relatedOrderType, refundToken, refundNeedDecision,
                t.getCsatScore(), t.getCsatComment(),
                t.getCreatedAt(), t.getResolvedAt());
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

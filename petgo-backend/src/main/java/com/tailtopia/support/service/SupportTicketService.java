package com.tailtopia.support.service;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.domain.ContactType;
import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.domain.TicketAttachment;
import com.tailtopia.support.domain.TicketInternalNote;
import com.tailtopia.support.domain.TicketLabel;
import com.tailtopia.support.domain.TicketLabelType;
import com.tailtopia.support.dto.SupportTicketView;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import com.tailtopia.support.repository.TicketAttachmentRepository;
import com.tailtopia.support.repository.TicketInternalNoteRepository;
import com.tailtopia.support.repository.TicketLabelRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 客服工单 service（Story 4.1，FR-52，AB-5）。用户建单/查单 + admin 内部备注原语。
 * 同构范式 {@link com.tailtopia.moderation.service.ReportService}。
 *
 * <p>隐私护栏：{@code contact_value}/正文为 PII，**日志绝不记录**（仅记 token/userId/计数）。
 * 用户视图 {@link SupportTicketView} **绝不含**内部备注/handled_by 等内部字段（D-3 契约红线）。
 */
@Service
public class SupportTicketService {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketService.class);
    private static final int MAX_ATTACHMENTS = 5;

    private final FeedbackTicketRepository tickets;
    private final TicketAttachmentRepository attachments;
    private final TicketLabelRepository labels;
    private final TicketInternalNoteRepository internalNotes;
    private final CardTokenGenerator tokenGenerator;
    private final ConsultOrderRepository orders;

    public SupportTicketService(FeedbackTicketRepository tickets, TicketAttachmentRepository attachments,
            TicketLabelRepository labels, TicketInternalNoteRepository internalNotes,
            CardTokenGenerator tokenGenerator, ConsultOrderRepository orders) {
        this.tickets = tickets;
        this.attachments = attachments;
        this.labels = labels;
        this.internalNotes = internalNotes;
        this.tokenGenerator = tokenGenerator;
        this.orders = orders;
    }

    /**
     * 建工单（用户）：原子建主表(OPEN) + ≤5 附件（超限 422）+ 标签去重 + relatedOrderToken 归属校验。
     * {@code contactType}/{@code labels} 非法 → 422。返回不可枚举 {@code ticketToken}。
     */
    @Transactional
    public String createTicket(long userId, String subject, String body, String contactTypeRaw,
            String contactValue, Boolean needContact, String relatedOrderToken,
            List<String> labelsRaw, List<String> attachmentObjectKeys) {

        List<String> keys = attachmentObjectKeys == null ? List.of() : attachmentObjectKeys;
        if (keys.size() > MAX_ATTACHMENTS) {
            throw AppException.validation("附件最多 " + MAX_ATTACHMENTS + " 张");
        }

        ContactType contactType = parseContactType(contactTypeRaw);
        LinkedHashSet<TicketLabelType> dedupedLabels = parseLabels(labelsRaw);
        Long relatedOrderId = resolveRelatedOrder(userId, relatedOrderToken);
        boolean needContactCustomer = needContact == null || needContact;

        String token = tokenGenerator.generate();
        FeedbackTicket ticket = tickets.save(FeedbackTicket.create(
                userId, token, subject, body, contactType, contactValue, needContactCustomer, relatedOrderId));

        for (String key : keys) {
            attachments.save(TicketAttachment.of(ticket.getId(), key));
        }
        for (TicketLabelType label : dedupedLabels) {
            labels.save(TicketLabel.of(ticket.getId(), label));
        }

        // 脱敏日志：绝不记 contactValue/正文（PII 护栏），仅 token/userId/计数。
        log.info("工单创建 ticket={} user={} labels={} attachments={}",
                token, userId, dedupedLabels.size(), keys.size());
        return token;
    }

    /** 用户查看单个工单：owner 校验，非本人 → 404（防枚举）。视图排除内部字段。 */
    @Transactional(readOnly = true)
    public SupportTicketView viewForUser(long userId, String ticketToken) {
        FeedbackTicket ticket = tickets.findByTicketToken(ticketToken)
                .filter(t -> t.getUserId() == userId)
                .orElseThrow(() -> AppException.notFound("工单不存在"));
        return toView(ticket);
    }

    /** 我的工单列表（created_at 倒序）。 */
    @Transactional(readOnly = true)
    public List<SupportTicketView> myTickets(long userId) {
        List<SupportTicketView> result = new ArrayList<>();
        for (FeedbackTicket t : tickets.findByUserIdOrderByCreatedAtDesc(userId)) {
            result.add(toView(t));
        }
        return result;
    }

    /**
     * 加内部备注（admin 原语，Story 4.1 建；UI 在 4-4/4-7）。**用户不可见**。
     * 校验工单存在（防悬空备注）。
     */
    @Transactional
    public void addInternalNote(long ticketId, long adminId, String note) {
        if (!tickets.existsById(ticketId)) {
            throw AppException.notFound("工单不存在");
        }
        internalNotes.save(TicketInternalNote.of(ticketId, adminId, note));
    }

    // ---- 内部辅助 ----

    private ContactType parseContactType(String raw) {
        try {
            return ContactType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw AppException.validation("联系方式类型非法");
        }
    }

    private LinkedHashSet<TicketLabelType> parseLabels(List<String> labelsRaw) {
        LinkedHashSet<TicketLabelType> result = new LinkedHashSet<>();
        if (labelsRaw == null) {
            return result;
        }
        for (String raw : labelsRaw) {
            try {
                result.add(TicketLabelType.valueOf(raw));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw AppException.validation("工单标签非法：" + raw);
            }
        }
        return result;
    }

    /**
     * 解析 relatedOrderToken → related_order_id：校验订单属本人；不符或不存在则**忽略存 null**
     * （OPEN-1 宽松，用户可能误传；退款工单 4-3 才严格绑单）。
     */
    private Long resolveRelatedOrder(long userId, String relatedOrderToken) {
        if (!StringUtils.hasText(relatedOrderToken)) {
            return null;
        }
        Optional<ConsultOrder> order = orders.findByOrderToken(relatedOrderToken);
        if (order.isPresent() && order.get().getUserId() == userId) {
            return order.get().getId();
        }
        return null;
    }

    private SupportTicketView toView(FeedbackTicket t) {
        List<String> labelNames = new ArrayList<>();
        for (TicketLabel l : labels.findByTicketIdOrderByIdAsc(t.getId())) {
            labelNames.add(l.getLabel().name());
        }
        // 本 story（OPEN-2）返回附件 objectKey；展示时的现签 URL 留 4-2（用户）/4-4（admin），
        // 复用 shared/media SignedUrlService（前端已有 media 签名流），避免本 story 耦合 OSS 凭证。
        List<String> attachmentObjectKeys = new ArrayList<>();
        for (TicketAttachment a : attachments.findByTicketIdOrderByIdAsc(t.getId())) {
            attachmentObjectKeys.add(a.getObjectKey());
        }
        return new SupportTicketView(
                t.getTicketToken(),
                t.getSubject(),
                t.getBody(),
                t.getContactType().name(),
                t.getContactValue(),
                t.isNeedContactCustomer(),
                t.isContactedCustomer(),
                t.getStatus().name(),
                labelNames,
                attachmentObjectKeys,
                t.getCsatScore(),
                t.getCsatComment(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getResolvedAt());
    }
}

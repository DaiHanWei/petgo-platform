package com.tailtopia.support.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.domain.ContactType;
import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.domain.TicketAttachment;
import com.tailtopia.support.domain.TicketInternalNote;
import com.tailtopia.support.domain.TicketLabel;
import com.tailtopia.support.domain.TicketLabelType;
import com.tailtopia.support.domain.TicketStatus;
import com.tailtopia.support.dto.SupportTicketView;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import com.tailtopia.support.repository.TicketAttachmentRepository;
import com.tailtopia.support.repository.TicketInternalNoteRepository;
import com.tailtopia.support.repository.TicketLabelRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final NotificationService notifications;
    private final AdminAuditService audit;

    /** CSAT 评价窗口（天，Story 4.7）；结案后 {@code csat_deadline=resolved_at+N天}，超期未评 scanner 静默关闭。 */
    @Value("${petgo.support.csat-window-days:7}")
    private int csatWindowDays;

    public SupportTicketService(FeedbackTicketRepository tickets, TicketAttachmentRepository attachments,
            TicketLabelRepository labels, TicketInternalNoteRepository internalNotes,
            CardTokenGenerator tokenGenerator, ConsultOrderRepository orders,
            NotificationService notifications, AdminAuditService audit) {
        this.tickets = tickets;
        this.attachments = attachments;
        this.labels = labels;
        this.internalNotes = internalNotes;
        this.tokenGenerator = tokenGenerator;
        this.orders = orders;
        this.notifications = notifications;
        this.audit = audit;
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

    /**
     * 客服结案（Story 4.7，「已联系+已解决」，{@code support.handle} 权限在控制器门控）。仅 OPEN/IN_PROGRESS 可
     * （已 RESOLVED/CLOSED → 409）。置 RESOLVED + csat_deadline(+N天) + 发 {@code TICKET_RESOLVED}（结案）+
     * {@code CSAT_SURVEY}（邀评）通知（deep link 工单详情，targetRef=ticketToken，非随机）+ 审计。
     */
    @Transactional
    public void resolveTicket(String ticketToken, long adminId) {
        FeedbackTicket ticket = tickets.findByTicketToken(ticketToken)
                .orElseThrow(() -> AppException.notFound("工单不存在"));
        if (ticket.getStatus() != TicketStatus.OPEN && ticket.getStatus() != TicketStatus.IN_PROGRESS) {
            throw AppException.conflict("工单已结案，不可重复处理");
        }
        Instant deadline = Instant.now().plus(csatWindowDays, ChronoUnit.DAYS);
        ticket.markResolved(adminId, deadline);
        long userId = ticket.getUserId();
        // 结案通知 + CSAT 邀评（均 REQUIRES_NEW，无 PII，targetRef=ticketToken 供 App 定位工单详情）。
        notifications.send(userId, NotificationType.TICKET_RESOLVED,
                "工单已处理", "你的客服工单已处理完成，点击查看结果。",
                NotificationType.TICKET_RESOLVED.name(), ticketToken);
        notifications.send(userId, NotificationType.CSAT_SURVEY,
                "为本次服务打分", "花几秒评价客服服务，帮助我们做得更好。",
                NotificationType.CSAT_SURVEY.name(), ticketToken);
        audit.record(adminId, AuditActions.TICKET_RESOLVED, "feedback_ticket", ticketToken,
                "客服工单结案（已联系+已解决，已发结案/CSAT 通知）");
    }

    /**
     * 用户提交 CSAT（Story 4.7）。owner（非本人 404）+ 仅 RESOLVED 未评窗口内可（否则 409）。
     * 落 csat_score/comment + {@code CLOSED}（评价即闭环）。
     */
    @Transactional
    public void submitCsat(long userId, String ticketToken, int score, String comment) {
        FeedbackTicket ticket = tickets.findByTicketToken(ticketToken)
                .filter(t -> t.getUserId() == userId)
                .orElseThrow(() -> AppException.notFound("工单不存在"));
        if (ticket.getStatus() != TicketStatus.RESOLVED) {
            throw AppException.conflict("工单当前不可评价");
        }
        ticket.submitCsat((short) score, comment);
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

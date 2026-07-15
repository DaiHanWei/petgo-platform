package com.tailtopia.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 客服工单主表（Story 4.1，FR-52，AB-5）。用户建（OPEN）+ admin 处理（status 流转 4-4/4-7）。
 * 同构范式 {@link com.tailtopia.moderation.domain.ContentReport}。
 *
 * <p>CSAT/{@code csRating}/{@code handledBy}/{@code resolvedAt}/{@code contactedCustomer} 为**预留字段**，
 * 本 story 建列即 null/默认，不写不发（4-4 admin 处理 / 4-7 结案 CSAT 才填）。
 * {@code contactValue} 为 PII，**绝不记录到日志**。
 */
@Entity
@Table(name = "feedback_tickets")
public class FeedbackTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_token", nullable = false, length = 32, updatable = false)
    private String ticketToken;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body", nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_type", nullable = false, length = 16)
    private ContactType contactType;

    @Column(name = "contact_value", nullable = false, length = 255)
    private String contactValue;

    @Column(name = "need_contact_customer", nullable = false)
    private boolean needContactCustomer = true;

    @Column(name = "contacted_customer", nullable = false)
    private boolean contactedCustomer = false;

    @Column(name = "related_order_id")
    private Long relatedOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TicketStatus status = TicketStatus.OPEN;

    // ---- 预留列（本 story 不填；4-4/4-7 落地）----
    @Column(name = "csat_score")
    private Short csatScore;

    @Column(name = "csat_comment", length = 100)
    private String csatComment;

    @Column(name = "csat_deadline")
    private Instant csatDeadline;

    @Column(name = "cs_rating")
    private Short csRating;

    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
    // ---- /预留列 ----

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FeedbackTicket() {
    }

    /**
     * 建单工厂：置 OPEN + 不可枚举 token（由 {@code CardTokenGenerator} 生成后传入）。
     * {@code relatedOrderId} 由 service 解析 orderToken 并校验归属后传入（不符则 null）。
     */
    public static FeedbackTicket create(long userId, String ticketToken, String subject, String body,
            ContactType contactType, String contactValue, boolean needContactCustomer, Long relatedOrderId) {
        FeedbackTicket t = new FeedbackTicket();
        t.userId = userId;
        t.ticketToken = ticketToken;
        t.subject = subject;
        t.body = body;
        t.contactType = contactType;
        t.contactValue = contactValue;
        t.needContactCustomer = needContactCustomer;
        t.contactedCustomer = false;
        t.relatedOrderId = relatedOrderId;
        t.status = TicketStatus.OPEN;
        return t;
    }

    /**
     * 客服结案（Story 4.7，「已联系+已解决」单动作）：{@code contacted_customer=true} + {@code RESOLVED} +
     * {@code resolved_at}(now) + {@code handled_by} + {@code csat_deadline}（+7d，CSAT 窗口）。
     */
    public void markResolved(long handledBy, Instant csatDeadline) {
        this.contactedCustomer = true;
        this.status = TicketStatus.RESOLVED;
        this.resolvedAt = Instant.now();
        this.handledBy = handledBy;
        this.csatDeadline = csatDeadline;
    }

    /** 用户提交 CSAT（Story 4.7，1-5 分 + 评论）：落 csat + {@code CLOSED}（评价即闭环）。 */
    public void submitCsat(short score, String comment) {
        this.csatScore = score;
        this.csatComment = comment;
        this.status = TicketStatus.CLOSED;
    }

    /** 7 天未评自动关闭（Story 4.7 scanner）：{@code RESOLVED→CLOSED}（无 CSAT，静默）。 */
    public void autoClose() {
        this.status = TicketStatus.CLOSED;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTicketToken() {
        return ticketToken;
    }

    public Long getUserId() {
        return userId;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public ContactType getContactType() {
        return contactType;
    }

    public String getContactValue() {
        return contactValue;
    }

    public boolean isNeedContactCustomer() {
        return needContactCustomer;
    }

    public boolean isContactedCustomer() {
        return contactedCustomer;
    }

    public Long getRelatedOrderId() {
        return relatedOrderId;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public Short getCsatScore() {
        return csatScore;
    }

    public String getCsatComment() {
        return csatComment;
    }

    public Instant getCsatDeadline() {
        return csatDeadline;
    }

    public Short getCsRating() {
        return csRating;
    }

    public Long getHandledBy() {
        return handledBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

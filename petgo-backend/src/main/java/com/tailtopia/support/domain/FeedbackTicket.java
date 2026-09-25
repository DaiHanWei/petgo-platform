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

    /**
     * {@link #relatedOrderId} 归哪张表（Story 3-2 / AD-S7）。
     *
     * <p>🔴 <b>这一列是「串单」的唯一防线</b>：{@code consult_orders.id} 与
     * {@code shop_orders.id} 都是从 1 开始的自增 bigint，数值空间完全重叠 ——
     * 没有它，一个电商订单 id 会被当成同号的问诊单，连带把退款开到别人头上。
     *
     * <p>{@code relatedOrderId} 为 null 时本字段无意义（取 {@code CONSULT}）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "related_order_type", nullable = false, length = 16)
    private RelatedOrderType relatedOrderType = RelatedOrderType.CONSULT;

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
     * 关联订单的解析结果（Story 3-2）：id 与它归哪张表**成对出现**。
     *
     * <p>🔴 <b>刻意做成一个值对象而不是两个平行入参</b>：id 与 type 分开传，
     * 早晚会有人只传对一个 —— 而那正是串单的形态。
     */
    public record ResolvedOrder(long orderId, RelatedOrderType type) {
    }

    /**
     * 建单工厂：置 OPEN + 不可枚举 token（由 {@code CardTokenGenerator} 生成后传入）。
     * {@code related} 由 service 解析 orderToken 并校验归属后传入（不符则 null）。
     */
    public static FeedbackTicket create(long userId, String ticketToken, String subject, String body,
            ContactType contactType, String contactValue, boolean needContactCustomer,
            ResolvedOrder related) {
        FeedbackTicket t = new FeedbackTicket();
        t.userId = userId;
        t.ticketToken = ticketToken;
        t.subject = subject;
        t.body = body;
        t.contactType = contactType;
        t.contactValue = contactValue;
        t.needContactCustomer = needContactCustomer;
        t.contactedCustomer = false;
        t.relatedOrderId = related == null ? null : related.orderId();
        // id 为 null 时 type 无意义，取 CONSULT（列是 NOT NULL）。
        t.relatedOrderType = related == null ? RelatedOrderType.CONSULT : related.type();
        t.status = TicketStatus.OPEN;
        return t;
    }

    /**
     * 客服标记「已联系」（bug 20260922-524，与结案拆开）：只置 {@code contacted_customer=true}，
     * {@code OPEN} 顺势进 {@code IN_PROGRESS}（客服已接手；仍属「待处理」页签，只是离开「待联系」）。
     * 不动结案字段。调用方负责只在未结案时调用。
     *
     * @return 本次是否真的改了（已联系过再点返回 false —— 调用方据此不重复记审计）
     */
    public boolean markContacted(long handledBy) {
        if (this.contactedCustomer) {
            return false;
        }
        this.contactedCustomer = true;
        this.handledBy = handledBy;
        if (this.status == TicketStatus.OPEN) {
            this.status = TicketStatus.IN_PROGRESS;
        }
        return true;
    }

    /**
     * 客服结案（Story 4.7）：{@code RESOLVED} + {@code resolved_at}(now) + {@code handled_by} +
     * {@code csat_deadline}（+7d，CSAT 窗口）。
     *
     * <p>bug 20260922-524：<b>不再顺带写 {@code contacted_customer=true}</b> —— 「已联系」拆成独立动作，
     * 结案不要求先联系（有的单无需联系就能解决），联系与否以客服实际标记为准。
     */
    public void markResolved(long handledBy, Instant csatDeadline) {
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

    /**
     * 客服「忽略」（bug 20260922-524，方案 B：复用 {@code CLOSED}，不新增状态、不加迁移）。
     * 无效 / 重复 / 骚扰类工单直接关闭：不写 {@code resolved_at}、不设 {@code csat_deadline}
     * （因此不会进 CSAT 窗口，也不在 7 天自动关闭扫描集里）。调用方负责只在未结案时调用。
     */
    public void markIgnored(long handledBy) {
        this.status = TicketStatus.CLOSED;
        this.handledBy = handledBy;
    }

    /** 7 天未评自动关闭（Story 4.7 scanner）：{@code RESOLVED→CLOSED}（无 CSAT，静默）。 */
    public void autoClose() {
        this.status = TicketStatus.CLOSED;
    }

    /**
     * 客服补挂问诊单（AB-5B「关联订单」；归属校验由 service 层完成后传入）。
     *
     * <p>🔴 <b>刻意没有「只写 id 不写 type」的方法</b>（Story 3-2 删掉了原来的
     * {@code linkRelatedOrder(long)}）：留着就一定会有人调，然后 type 是错的 ——
     * 而 type 错了就是串单。两个动作必须绑在一起，绑在方法名上是最便宜的绑法。
     */
    public void linkConsultOrder(long orderId) {
        this.relatedOrderId = orderId;
        this.relatedOrderType = RelatedOrderType.CONSULT;
    }

    /** 客服补挂电商订单（Story 3-2）。归属校验由 service 层完成后传入。 */
    public void linkShopOrder(long orderId) {
        this.relatedOrderId = orderId;
        this.relatedOrderType = RelatedOrderType.SHOP;
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

    public RelatedOrderType getRelatedOrderType() {
        return relatedOrderType;
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

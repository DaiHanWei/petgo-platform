package com.tailtopia.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 工单附件（Story 4.1）。仅存 OSS {@code object_key}（复用 shared/media 预签名上传，展示时 SignedUrlService 现签）。
 * ≤5 校验在 {@code SupportTicketService.createTicket}。
 */
@Entity
@Table(name = "ticket_attachments")
public class TicketAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private Long ticketId;

    @Column(name = "object_key", nullable = false, length = 512, updatable = false)
    private String objectKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TicketAttachment() {
    }

    public static TicketAttachment of(long ticketId, String objectKey) {
        TicketAttachment a = new TicketAttachment();
        a.ticketId = ticketId;
        a.objectKey = objectKey;
        return a;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

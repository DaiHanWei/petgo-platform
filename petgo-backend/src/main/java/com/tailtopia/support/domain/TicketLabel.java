package com.tailtopia.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 工单标签（Story 4.1）。同工单去重（唯一 ticket_id+label，DB 约束 + service 去重）。
 */
@Entity
@Table(name = "ticket_labels")
public class TicketLabel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private Long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "label", nullable = false, length = 24, updatable = false)
    private TicketLabelType label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TicketLabel() {
    }

    public static TicketLabel of(long ticketId, TicketLabelType label) {
        TicketLabel l = new TicketLabel();
        l.ticketId = ticketId;
        l.label = label;
        return l;
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

    public TicketLabelType getLabel() {
        return label;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

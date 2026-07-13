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
 * 工单内部备注（Story 4.1，AB-5 隐私契约红线）。**用户绝不可见**——{@code SupportTicketView} 无此字段，
 * 仅 admin 视图（4-4）含。admin 加（{@code addInternalNote} 原语本 story 建，UI 在 4-4/4-7）。
 */
@Entity
@Table(name = "ticket_internal_notes")
public class TicketInternalNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private Long ticketId;

    @Column(name = "admin_id", nullable = false, updatable = false)
    private Long adminId;

    @Column(name = "note", nullable = false, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TicketInternalNote() {
    }

    public static TicketInternalNote of(long ticketId, long adminId, String note) {
        TicketInternalNote n = new TicketInternalNote();
        n.ticketId = ticketId;
        n.adminId = adminId;
        n.note = note;
        return n;
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

    public Long getAdminId() {
        return adminId;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

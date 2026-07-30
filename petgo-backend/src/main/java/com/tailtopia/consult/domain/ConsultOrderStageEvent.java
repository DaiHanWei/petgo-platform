package com.tailtopia.consult.domain;

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
 * 兽医咨询订单节点事件（Story 3.1，{@code consult_order_stage_events} 子表）。<b>append-only</b>：
 * 每个节点（接单/支付/会话起止/退款）一行 INSERT，绝不 UPDATE/DELETE 旧行（对账/审计留痕，
 * 比照 {@code ledger_entries} append-only 铁律）。故无 {@code updated_at}、无 setter。
 */
@Entity
@Table(name = "consult_order_stage_events")
public class ConsultOrderStageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consult_order_id", nullable = false, updatable = false)
    private Long consultOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24, updatable = false)
    private ConsultStageEvent eventType;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "note", length = 255, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConsultOrderStageEvent() {
    }

    public static ConsultOrderStageEvent of(long consultOrderId, ConsultStageEvent eventType,
            Instant occurredAt, String note) {
        ConsultOrderStageEvent e = new ConsultOrderStageEvent();
        e.consultOrderId = consultOrderId;
        e.eventType = eventType;
        e.occurredAt = occurredAt;
        e.note = note;
        return e;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getConsultOrderId() {
        return consultOrderId;
    }

    public ConsultStageEvent getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

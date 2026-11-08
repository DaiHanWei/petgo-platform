package com.tailtopia.shop.logistics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Biteship Webhook 收件行（v1.4.2 shop Story 5.1 AC6 / AC7）。
 *
 * <p>🔴 载荷<b>只当通知</b>：处理一律按 {@code biteship_order_id} 回查 {@code GET /v1/orders/:id}，载荷里的
 * status / waybill / price 不参与任何决策。{@code payload} 只存白名单键（不含司机姓名 / 电话等），仅供排障。
 */
@Entity
@Table(name = "biteship_webhook_events")
public class BiteshipWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "biteship_order_id", length = 64)
    private String biteshipOrderId;

    @Column(name = "event", nullable = false, length = 40)
    private String event;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 200)
    private String lastError;

    @Column(name = "alerted_at")
    private Instant alertedAt;

    protected BiteshipWebhookEvent() {
    }

    public static BiteshipWebhookEvent receive(String biteshipOrderId, String event, Map<String, Object> payload,
            Instant now) {
        BiteshipWebhookEvent e = new BiteshipWebhookEvent();
        e.biteshipOrderId = biteshipOrderId == null || biteshipOrderId.length() <= 64 ? biteshipOrderId
                : biteshipOrderId.substring(0, 64);
        String ev = event == null || event.isBlank() ? "order.status" : event;
        e.event = ev.length() <= 40 ? ev : ev.substring(0, 40);
        e.payload = new LinkedHashMap<>(payload);
        e.receivedAt = now;
        return e;
    }

    public void markProcessed(Instant at) {
        this.processedAt = at;
    }

    /** 处理失败：attempts + 1、记原因（≤ 200 字）。返回 +1 后的次数。 */
    public int recordFailure(String error) {
        this.attempts++;
        String e = error == null ? "UNKNOWN" : error;
        this.lastError = e.length() > 200 ? e.substring(0, 200) : e;
        return attempts;
    }

    public void markAlerted(Instant at) {
        this.alertedAt = at;
    }

    public boolean isDone() {
        return processedAt != null || alertedAt != null;
    }

    public Long getId() {
        return id;
    }

    public String getBiteshipOrderId() {
        return biteshipOrderId;
    }

    public String getEvent() {
        return event;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getAlertedAt() {
        return alertedAt;
    }
}

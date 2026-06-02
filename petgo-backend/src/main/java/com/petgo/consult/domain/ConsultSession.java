package com.petgo.consult.domain;

import com.petgo.shared.error.AppException;
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
 * 兽医咨询会话（Story 5.3 创建 {@code consult_sessions} 表）。Epic 5 会话状态机的数据根。
 *
 * <p>状态迁移合法性集中在实体方法（service 调用），非法迁移抛 {@link AppException} → ProblemDetail。
 * 本故事落 {@code →WAITING}（{@link #startWaiting}）与 {@code WAITING→CANCELLED}（{@link #cancel}）；
 * {@code WAITING→IN_PROGRESS}（{@link #accept}）留方法签名供 5.5 调用。时间戳 UTC。
 */
@Entity
@Table(name = "consult_sessions")
public class ConsultSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 接单后填（Story 5.5）。 */
    @Column(name = "vet_id")
    private Long vetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SessionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private ConsultSource source = ConsultSource.DIRECT;

    /** 超时(1min)计时基准；继续等待时重置。 */
    @Column(name = "waiting_started_at")
    private Instant waitingStartedAt;

    /** 腾讯 IM 会话标识（Story 5.5）。 */
    @Column(name = "im_conversation_id", length = 128)
    private String imConversationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConsultSession() {
    }

    /** 发起咨询：进入 WAITING + 入待接单队列（队列由 service 维护）。 */
    public static ConsultSession startWaiting(long userId, ConsultSource source) {
        ConsultSession s = new ConsultSession();
        s.userId = userId;
        s.source = source;
        s.status = SessionStatus.WAITING;
        s.waitingStartedAt = Instant.now();
        return s;
    }

    /** 继续等待：重置计时基准（请求保留队列，不改状态）。 */
    public void resetWaiting() {
        requireStatus(SessionStatus.WAITING, "仅等待中的咨询可继续等待");
        this.waitingStartedAt = Instant.now();
    }

    /** 用户主动取消：WAITING → CANCELLED（出队由 service 做）。 */
    public void cancel() {
        requireStatus(SessionStatus.WAITING, "仅等待中的咨询可取消");
        this.status = SessionStatus.CANCELLED;
    }

    /** 兽医接单：WAITING → IN_PROGRESS（Story 5.5 调用）。 */
    public void accept(long vetId, String imConversationId) {
        requireStatus(SessionStatus.WAITING, "仅等待中的咨询可被接单");
        this.vetId = vetId;
        this.imConversationId = imConversationId;
        this.status = SessionStatus.IN_PROGRESS;
    }

    private void requireStatus(SessionStatus expected, String message) {
        if (this.status != expected) {
            throw AppException.conflict(message);
        }
    }

    /** 是否已等待超过 {@code seconds} 秒仍未接单（前端轮询判断超时弹层用）。 */
    public boolean isTimedOut(long seconds) {
        return status == SessionStatus.WAITING
                && waitingStartedAt != null
                && Instant.now().isAfter(waitingStartedAt.plusSeconds(seconds));
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

    public Long getUserId() {
        return userId;
    }

    public Long getVetId() {
        return vetId;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public ConsultSource getSource() {
        return source;
    }

    public Instant getWaitingStartedAt() {
        return waitingStartedAt;
    }

    public String getImConversationId() {
        return imConversationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.tailtopia.consult.domain;

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
 * 兽医咨询请求（Story 3.1，{@code consult_requests} 付费前临时态）。A-5 两表拆分的「付费前」半。
 *
 * <p><b>状态迁移走 repo 单列 CAS，不走实体方法</b>（H-4：accept=UPDATE state WHERE state=QUEUEING、
 * cancel/timeout=DELETE WHERE state=QUEUEING，行锁串行、非时间戳比较）。本实体是数据持有 + 建行工厂；
 * 取消/超时物理删行（无 CANCELLED 态）。支付成功即转 {@code consult_orders} 并删本行。时间戳 UTC。
 */
@Entity
@Table(name = "consult_requests")
public class ConsultRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_token", nullable = false, length = 32, updatable = false)
    private String requestToken;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "pet_profile_id", nullable = false, updatable = false)
    private Long petProfileId;

    /** 接单后填（CAS tryAccept 时）。 */
    @Column(name = "vet_id")
    private Long vetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private ConsultRequestState state;

    /** 入队 1min 截止（服务端权威计时）。 */
    @Column(name = "queue_deadline_at")
    private Instant queueDeadlineAt;

    /** 接单后 +1.5min 支付截止。 */
    @Column(name = "pay_deadline_at")
    private Instant payDeadlineAt;

    /** 跳充值暂停锚（A-4 暂停顺延）。 */
    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "rebroadcast_count", nullable = false)
    private int rebroadcastCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConsultRequest() {
    }

    /** 入队：建 QUEUEING 请求，rebroadcast_count=0，vet_id/pay_deadline 待接单填。 */
    public static ConsultRequest queue(long userId, long petProfileId, String requestToken,
            Instant queueDeadlineAt) {
        ConsultRequest r = new ConsultRequest();
        r.userId = userId;
        r.petProfileId = petProfileId;
        r.requestToken = requestToken;
        r.state = ConsultRequestState.QUEUEING;
        r.queueDeadlineAt = queueDeadlineAt;
        r.rebroadcastCount = 0;
        return r;
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

    public String getRequestToken() {
        return requestToken;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public Long getVetId() {
        return vetId;
    }

    public ConsultRequestState getState() {
        return state;
    }

    public Instant getQueueDeadlineAt() {
        return queueDeadlineAt;
    }

    public Instant getPayDeadlineAt() {
        return payDeadlineAt;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public int getRebroadcastCount() {
        return rebroadcastCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

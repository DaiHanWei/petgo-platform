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
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    // ===== 病例（V84 / Story 3.2 [OPEN] 收口）：兽医接单前据此判断是否接单 =====

    /** 来源：DIRECT（用户自填病例）| AI_UPGRADE（分诊升级，AI 上下文由后端从 triage 拉取快照）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16, updatable = false)
    private ConsultSource source = ConsultSource.DIRECT;

    /** 来源分诊任务（source=AI_UPGRADE 时填）。 */
    @Column(name = "triage_task_id", updatable = false)
    private Long triageTaskId;

    /** AI 危险等级快照：GREEN|YELLOW，DIRECT 为 null。<b>绝不含 RED</b>（红色态零兽医引流）。 */
    @Column(name = "ai_danger_level", length = 8, updatable = false)
    private String aiDangerLevel;

    /** 症状描述（健康数据，日志严禁明文）。 */
    @Column(name = "symptom_text", updatable = false)
    private String symptomText;

    /** 私密桶对象 key 列表（引用，取用时现签 URL；绝不存签名 URL）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_object_keys", updatable = false)
    private List<String> imageObjectKeys;

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

    /**
     * 自填病例（DIRECT）：用户提交的症状 + 私密图 key，无 AI 评级。
     * 照 {@link ConsultSession#bindDirectCase}，{@code aiDangerLevel} 留空。
     */
    public void bindDirectCase(String symptomText, List<String> imageObjectKeys) {
        this.source = ConsultSource.DIRECT;
        this.symptomText = symptomText;
        this.imageObjectKeys = imageObjectKeys;
    }

    /**
     * 分诊升级上下文（AI_UPGRADE，D2）：升级当下把 AI 评级/症状/图定格为快照，
     * triage 后续变更不影响已入队请求（照 V15「快照定格」语义）。
     *
     * <p><b>调用方须已兜底拒绝 RED</b>——本方法不校验（库 CHECK 是最后一道，不是第一道）。
     */
    public void bindAiContext(long triageTaskId, String aiDangerLevel, String symptomText,
            List<String> imageObjectKeys) {
        this.source = ConsultSource.AI_UPGRADE;
        this.triageTaskId = triageTaskId;
        this.aiDangerLevel = aiDangerLevel;
        this.symptomText = symptomText;
        this.imageObjectKeys = imageObjectKeys;
    }

    /** 是否有可展示给兽医的病例（自填症状/图 或 AI 上下文）。照 {@link ConsultSession#hasCase}。 */
    public boolean hasCase() {
        return (symptomText != null && !symptomText.isBlank())
                || (imageObjectKeys != null && !imageObjectKeys.isEmpty());
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

    public ConsultSource getSource() {
        return source;
    }

    public Long getTriageTaskId() {
        return triageTaskId;
    }

    public String getAiDangerLevel() {
        return aiDangerLevel;
    }

    public String getSymptomText() {
        return symptomText;
    }

    public List<String> getImageObjectKeys() {
        return imageObjectKeys;
    }
}

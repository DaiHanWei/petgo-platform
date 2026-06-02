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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    // ===== AI 上下文快照（Story 5.4，source=AI_UPGRADE 时填）=====

    @Column(name = "triage_task_id")
    private Long triageTaskId;

    /** GREEN | YELLOW（绝不含 RED；红线由 service 兜底拒绝 + 库约束）。 */
    @Column(name = "ai_danger_level", length = 8)
    private String aiDangerLevel;

    /** 症状描述快照（健康数据，日志严禁明文）。 */
    @Column(name = "ai_symptom_text")
    private String aiSymptomText;

    /** 私密桶对象 key 列表（引用，取用时现签 URL；绝不存签名 URL）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_image_refs")
    private List<String> aiImageRefs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 乐观锁版本（Story 5.5 接单并发抢单 CAS）。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // ===== 会话收尾 + 评分门（Story 5.6）=====

    /** 评分门 30min 计时基准（PENDING_CLOSE 起）。 */
    @Column(name = "pending_close_started_at")
    private Instant pendingCloseStartedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "closed_reason", length = 16)
    private ClosedReason closedReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "rating_prompt_state", nullable = false, length = 16)
    private RatingPromptState ratingPromptState = RatingPromptState.NONE;

    // ===== 封禁中断（Story 5.7）=====

    @Enumerated(EnumType.STRING)
    @Column(name = "interrupted_reason", length = 16)
    private InterruptReason interruptedReason;

    @Column(name = "interrupted_at")
    private Instant interruptedAt;

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

    /**
     * 绑定 AI 上下文快照（Story 5.4，source=AI_UPGRADE）。
     * 红线：{@code dangerLevel} 仅 GREEN/YELLOW（RED 由 service 兜底拒绝，绝不到达此处）。
     */
    public void bindAiContext(Long triageTaskId, String dangerLevel, String symptomText, List<String> imageRefs) {
        this.triageTaskId = triageTaskId;
        this.aiDangerLevel = dangerLevel;
        this.aiSymptomText = symptomText;
        this.aiImageRefs = imageRefs;
    }

    public boolean hasAiContext() {
        return source == ConsultSource.AI_UPGRADE && aiDangerLevel != null;
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

    /**
     * 兽医接单：WAITING → IN_PROGRESS（Story 5.5）。并发抢单由 {@code @Version} 乐观锁裁决
     * （saveAndFlush 时仅一人成功，其余 OptimisticLock → 「已被接走」）。
     */
    public void markInProgress(long vetId) {
        requireStatus(SessionStatus.WAITING, "该咨询已被接走");
        this.vetId = vetId;
        this.status = SessionStatus.IN_PROGRESS;
    }

    /** 接单成功后绑定 IM 会话标识。 */
    public void attachImConversation(String imConversationId) {
        this.imConversationId = imConversationId;
    }

    /** 兽医结束：IN_PROGRESS → PENDING_CLOSE（非立即关闭，启动 30min 评分门计时）。 */
    public void endByVet() {
        requireStatus(SessionStatus.IN_PROGRESS, "仅进行中的会话可结束");
        this.status = SessionStatus.PENDING_CLOSE;
        this.pendingCloseStartedAt = Instant.now();
    }

    /** 用户评分关闭：PENDING_CLOSE → CLOSED（RATED）。 */
    public void closeRated() {
        requireStatus(SessionStatus.PENDING_CLOSE, "本次会话不在可评分状态");
        this.status = SessionStatus.CLOSED;
        this.closedReason = ClosedReason.RATED;
        this.ratingPromptState = RatingPromptState.NONE;
    }

    /** 30min 超时未评：PENDING_CLOSE → CLOSED（UNRATED），置补弹 PENDING。 */
    public void closeUnrated() {
        requireStatus(SessionStatus.PENDING_CLOSE, "本次会话不在可关闭状态");
        this.status = SessionStatus.CLOSED;
        this.closedReason = ClosedReason.UNRATED;
        this.ratingPromptState = RatingPromptState.PENDING;
    }

    /** 补弹已展示 → 置 PROMPTED（不再弹）。 */
    public void markRatingPrompted() {
        this.ratingPromptState = RatingPromptState.PROMPTED;
    }

    /**
     * 封禁中断（Story 5.7）：IN_PROGRESS / PENDING_CLOSE → INTERRUPTED（终态，不评分、不存档）。
     * 非进行中态调用即抛错（幂等保护：已终态不再中断）。
     */
    public void interrupt(InterruptReason reason) {
        if (status != SessionStatus.IN_PROGRESS && status != SessionStatus.PENDING_CLOSE) {
            throw AppException.conflict("会话不在可中断状态");
        }
        this.status = SessionStatus.INTERRUPTED;
        this.interruptedReason = reason;
        this.interruptedAt = Instant.now();
        this.ratingPromptState = RatingPromptState.NONE; // 中断不评分
    }

    /** 补弹后用户补评分（会话已 CLOSED，仅消除补弹标记，不改 closed_reason 的历史事实）。 */
    public void clearRatingPrompt() {
        this.ratingPromptState = RatingPromptState.NONE;
    }

    /** 是否已过 PENDING_CLOSE 评分门 {@code seconds} 秒（定时扫描判断超时关闭用）。 */
    public boolean isRatingGateExpired(long seconds) {
        return status == SessionStatus.PENDING_CLOSE
                && pendingCloseStartedAt != null
                && Instant.now().isAfter(pendingCloseStartedAt.plusSeconds(seconds));
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

    public Long getTriageTaskId() {
        return triageTaskId;
    }

    public String getAiDangerLevel() {
        return aiDangerLevel;
    }

    public String getAiSymptomText() {
        return aiSymptomText;
    }

    public List<String> getAiImageRefs() {
        return aiImageRefs;
    }

    public Instant getPendingCloseStartedAt() {
        return pendingCloseStartedAt;
    }

    public ClosedReason getClosedReason() {
        return closedReason;
    }

    public RatingPromptState getRatingPromptState() {
        return ratingPromptState;
    }

    public InterruptReason getInterruptedReason() {
        return interruptedReason;
    }

    public Instant getInterruptedAt() {
        return interruptedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

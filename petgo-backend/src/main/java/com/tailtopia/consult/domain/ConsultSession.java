package com.tailtopia.consult.domain;

import com.tailtopia.shared.error.AppException;
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

    /** 兽医最终诊断（Story C，结构化 JSONB，结束会话时定格）。健康数据：禁日志。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vet_diagnosis")
    private VetDiagnosis vetDiagnosis;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 乐观锁版本（Story 5.5 接单并发抢单 CAS）。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 退单计数（Story 5.3 R2，决策 F11）：兽医退单 IN_PROGRESS→WAITING 的累计次数；&gt;2 为异常信号供运营。 */
    @Column(name = "release_count", nullable = false)
    private int releaseCount;

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

    // ===== 封禁挂起（Story 3.8，H-5）=====

    /** 挂起截止（付费会话被封禁挂起时置，服务端权威 15min；非空=挂起中，仍 IN_PROGRESS）。 */
    @Column(name = "suspend_deadline_at")
    private Instant suspendDeadlineAt;

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

    /**
     * 直连问诊病例（Story F：用户自填症状 + 私密图 key，无 AI 评级）。
     * 复用 AI 上下文同列存储（{@code ai_symptom_text}/{@code ai_image_refs}），{@code ai_danger_level} 留空。
     */
    public void bindDirectCase(String symptomText, List<String> imageObjectKeys) {
        this.aiSymptomText = symptomText;
        this.aiImageRefs = imageObjectKeys;
    }

    /** 是否有可展示给兽医的病例（AI 升级上下文 或 直连自填症状/图）。 */
    public boolean hasCase() {
        return hasAiContext()
                || (aiSymptomText != null && !aiSymptomText.isBlank())
                || (aiImageRefs != null && !aiImageRefs.isEmpty());
    }

    /**
     * 注销匿名化（Story 7.3，决策 D1）：剥 user PII（解关联 {@code userId}）+ 清 AI 上下文 PII
     * （症状文字/私密图引用，图片由级联删除处理），保留 {@code danger_level}/{@code vetId} 等运营/历史价值。
     */
    public void anonymize() {
        this.userId = null;
        this.aiSymptomText = null;
        this.aiImageRefs = null;
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
     * 兽医退单（Story 5.3 R2，决策 F11）：IN_PROGRESS → WAITING，解绑兽医 + 清 IM 会话 + 重置等待计时，
     * 退单计数 +1。请求重新入队广播由 service 做。并发互斥沿用 {@code @Version} 乐观锁（saveAndFlush 裁决）。
     * 每请求最多正常退单 2 次；{@link #isAbnormalReleaseCount()} 为真（&gt;2）即异常信号，由运营人工处理。
     */
    public void release() {
        requireStatus(SessionStatus.IN_PROGRESS, "仅进行中的会话可退单");
        this.status = SessionStatus.WAITING;
        this.vetId = null;
        this.imConversationId = null;
        this.waitingStartedAt = Instant.now();
        this.releaseCount += 1;
    }

    /** 退单次数是否已超过正常上限（&gt;2）——异常信号，运营人工处理（F11）。 */
    public boolean isAbnormalReleaseCount() {
        return releaseCount > 2;
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

    /** 结束前定格最终诊断（Story C）。Diagnosa 必填由 web 层校验。 */
    public void recordDiagnosis(VetDiagnosis diagnosis) {
        this.vetDiagnosis = diagnosis;
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
        this.suspendDeadlineAt = null; // 终态清挂起锚
    }

    /**
     * 封禁挂起（Story 3.8，H-5）：付费会话被封禁时置 15min 挂起截止，<b>保持 IN_PROGRESS</b>（不改状态机、IM 仍可用、
     * 用户在控制、不被劫持）。到期/用户逃生 → {@link #interrupt}(VET_BANNED) + 退款。仅 IN_PROGRESS 可挂起。
     */
    public void suspend(Instant deadline) {
        requireStatus(SessionStatus.IN_PROGRESS, "仅进行中的会话可挂起");
        this.suspendDeadlineAt = deadline;
    }

    /** 补弹后用户补评分（会话已 CLOSED，仅消除补弹标记，不改 closed_reason 的历史事实）。 */
    public void clearRatingPrompt() {
        this.ratingPromptState = RatingPromptState.NONE;
    }

    /**
     * 终态时间（历史展示 + 排序的单一口径）：一律取**结束那一刻的冻结时间戳**——中断取
     * {@code interruptedAt}，兽医结束（PENDING_CLOSE 及其后 CLOSED）取 {@code pendingCloseStartedAt}，
     * 二者皆为一次性写入、后续不变。仅在未走结束流程的非终态（WAITING/IN_PROGRESS，不进历史）才回退
     * {@code updatedAt}/{@code createdAt}。
     *
     * <p>Bug 20260706-264：原实现回退 {@code updatedAt}，而 {@code updatedAt} 会被后续动作 bump
     * （30min 窗口到期 closeUnrated、补评分等），导致同一条问诊的显示时间与排序位置「漂移」。改用
     * 冻结的 {@code pendingCloseStartedAt} 后，兽医点结束即定格，之后 IM 新消息/自动关闭均不改时间、不重排。
     * 用户侧 {@code ConsultHistoryService} 与兽医侧 {@code VetConsultService} 共用此口径。
     */
    public Instant terminalAt() {
        if (interruptedAt != null) {
            return interruptedAt;
        }
        if (pendingCloseStartedAt != null) {
            return pendingCloseStartedAt;
        }
        return updatedAt != null ? updatedAt : createdAt;
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

    public int getReleaseCount() {
        return releaseCount;
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

    public VetDiagnosis getVetDiagnosis() {
        return vetDiagnosis;
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

    public Instant getSuspendDeadlineAt() {
        return suspendDeadlineAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

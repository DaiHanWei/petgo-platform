package com.tailtopia.triage.domain;

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
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 分诊任务（Story 4.1 创建 {@code triage_tasks} 表）。AI 智能分诊的异步状态机数据根。
 *
 * <p>弹性字段：{@code imageObjectKeys}/{@code geminiRaw}/{@code parsedResult} 映射 JSONB；
 * {@code status}/{@code dangerLevel} 落 varchar。时间戳 UTC。
 *
 * <p>护栏：{@code imageObjectKeys} 仅存私密桶对象 key（不可枚举），<b>绝不存签名 URL</b>；
 * {@code dangerLevel} 是后置校验产物（4.2 只升不降），非模型最终裁决。
 */
@Entity
@Table(name = "triage_tasks")
public class TriageTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 存档绑定（FR-16，Story 2.5/4.4 承接），本故事仅预留。 */
    @Column(name = "pet_id")
    private Long petId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TriageStatus status = TriageStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "danger_level", length = 8)
    private DangerLevel dangerLevel;

    @Column(name = "symptom_text")
    private String symptomText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_object_keys")
    private List<String> imageObjectKeys;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gemini_raw")
    private Map<String, Object> geminiRaw;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_result")
    private Map<String, Object> parsedResult;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    /** 回复语言（submit 时按 Accept-Language 归一 id/en，默认 en）。处理时喂 Gemini，绝不中文。 */
    @Column(name = "response_locale", length = 8)
    private String responseLocale;

    /**
     * 详建解锁来源（Story 2.2）。{@code markDone}（成功生成）时初始化为 {@link UnlockSource#LOCKED}；
     * PENDING/FAILED 恒 null（无可解锁详建，「生成失败不建记录」）。跃迁见 {@link #unlock}（不可覆盖）。
     * 红色详建永不锁在响应层单点判定，与本字段无关。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "unlock_source", length = 16)
    private UnlockSource unlockSource;

    /** 付费解锁渠道（Story 2.2）。仅 {@link UnlockSource#PAID} 有值，其余恒 null。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "unlock_channel", length = 16)
    private UnlockChannel unlockChannel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TriageTask() {
    }

    /** 受理：建任务，status=PENDING、retry_count=0、danger_level 待定。 */
    public static TriageTask submit(long userId, Long petId, String symptomText,
            List<String> imageObjectKeys, String idempotencyKey, String responseLocale) {
        TriageTask t = new TriageTask();
        t.userId = userId;
        t.petId = petId;
        t.symptomText = symptomText;
        t.imageObjectKeys = imageObjectKeys;
        t.idempotencyKey = idempotencyKey;
        t.responseLocale = responseLocale;
        t.status = TriageStatus.PENDING;
        t.retryCount = 0;
        return t;
    }

    /** 置处理中（@Async 处理器领取时）。 */
    public void markProcessing() {
        this.status = TriageStatus.PROCESSING;
    }

    /** 解析完成：写最终级别 + 原始/解析 JSONB，置 DONE。{@code level} 为后置校验后的最终值。 */
    public void markDone(DangerLevel level, Map<String, Object> geminiRaw, Map<String, Object> parsedResult) {
        this.dangerLevel = level;
        this.geminiRaw = geminiRaw;
        this.parsedResult = parsedResult;
        this.status = TriageStatus.DONE;
        // Story 2.2：详建默认锁定，待 2-3 解锁。仅成功生成才置——失败走 markFailed 不经此，unlock_source 恒 null
        // （「生成失败不建记录」）。红色详建永不锁在响应层判定，与此无关（此处一律 LOCKED）。
        this.unlockSource = UnlockSource.LOCKED;
    }

    /** 失败回退：retry_count++ 并置回 PENDING 供重试。 */
    public void markRetry() {
        this.retryCount++;
        this.status = TriageStatus.PENDING;
    }

    /** 重试超限：置 FAILED 供前端降级。 */
    public void markFailed() {
        this.status = TriageStatus.FAILED;
    }

    /**
     * 解锁详建（Story 2.2，供 Story 2.3 在其解锁事务内调）。仅允许 {@code LOCKED/NULL → FREE_QUOTA/PAID}
     * 一次性跃迁——<b>一经写入不可覆盖</b>（架构 L105）：已是 {@code FREE_QUOTA/PAID} 时抛
     * {@link IllegalStateException}（2-3 视作幂等冲突）。{@code PAID} 必带 {@code channel}、
     * {@code FREE_QUOTA} 的 {@code channel} 必为 null。本 story 不接调用点，仅建方法 + 单测覆盖。
     */
    public void unlock(UnlockSource source, UnlockChannel channel) {
        if (source != UnlockSource.FREE_QUOTA && source != UnlockSource.PAID) {
            throw new IllegalArgumentException("unlock 目标只能是 FREE_QUOTA 或 PAID，实际=" + source);
        }
        if (this.unlockSource == UnlockSource.FREE_QUOTA || this.unlockSource == UnlockSource.PAID) {
            throw new IllegalStateException("unlock_source 一经写入不可覆盖，当前=" + this.unlockSource);
        }
        if (source == UnlockSource.PAID && channel == null) {
            throw new IllegalArgumentException("PAID 解锁必须带 channel");
        }
        if (source == UnlockSource.FREE_QUOTA && channel != null) {
            throw new IllegalArgumentException("FREE_QUOTA 解锁 channel 必须为空");
        }
        this.unlockSource = source;
        this.unlockChannel = channel;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPetId() {
        return petId;
    }

    public TriageStatus getStatus() {
        return status;
    }

    public DangerLevel getDangerLevel() {
        return dangerLevel;
    }

    public String getSymptomText() {
        return symptomText;
    }

    public List<String> getImageObjectKeys() {
        return imageObjectKeys;
    }

    public Map<String, Object> getGeminiRaw() {
        return geminiRaw;
    }

    public Map<String, Object> getParsedResult() {
        return parsedResult;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getResponseLocale() {
        return responseLocale;
    }

    public UnlockSource getUnlockSource() {
        return unlockSource;
    }

    public UnlockChannel getUnlockChannel() {
        return unlockChannel;
    }

    public Instant getCreatedAt() {
        return createdAt;
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
}

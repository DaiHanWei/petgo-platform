package com.tailtopia.content.domain;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 内容帖子（Story 2.3 创建 {@code content_posts} 表）。三类内容发布的数据根，Epic 3 复用。
 *
 * <p>弹性字段：{@code imageUrls} 映射 JSONB；{@code type}/{@code status}/{@code dangerLevel} 落 varchar。
 * 软删 {@code deletedAt}（为 Epic3 删除/注销级联准备）。时间戳 UTC。
 */
@Entity
@Table(name = "content_posts")
public class ContentPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ContentType type;

    @Column(name = "pet_id")
    private Long petId;

    @Column(name = "text", length = 1000)
    private String text;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_urls")
    private List<String> imageUrls;

    @Column(name = "danger_level", length = 8)
    private String dangerLevel;

    /** 成长日历事件日期（F9）：仅 GROWTH_MOMENT 有值，决定档案侧显示位置；与 createdAt 排序解耦。 */
    @Column(name = "event_date")
    private LocalDate eventDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PostStatus status = PostStatus.PUBLISHED;

    /**
     * 三方风险分（内容审核 story 2，D-CM2）：0.000–1.000，NUMERIC(4,3)。仅挂起帖（RISK_HIGH）落值；
     * PASS/降级/命中硬拦截不写（可空）。BigDecimal(precision=4,scale=3) 精确匹配列（避 float 比较误差 + validate 契约）。
     */
    @Column(name = "moderation_risk_score", precision = 4, scale = 3)
    private BigDecimal moderationRiskScore;

    /**
     * 入队原因（内容审核 story 2）：{@code RISK_HIGH}（评分 ≥0.8）/ {@code DEGRADED_FAILCLOSED}（三方降级）；
     * 非挂起为空。DB CHECK 约束取值域。
     */
    @Column(name = "review_reason", length = 24)
    private String reviewReason;

    /**
     * 内容版本键（内容审核 story 2 · D-CM3）：默认 1，每次编辑 +1；审核结果绑定入队时刻版本，
     * 出结果时版本已变 → 旧结果作废。帖子编辑端点在 V1 尚不存在，故当前恒为 1、守卫恒等（dormant）。
     */
    @Column(name = "content_version", nullable = false)
    private int contentVersion = 1;

    /**
     * 举报驱动 P0 自动预处置（内容审核 cm-6）：已发布帖被翻回「仅作者可见待判」挂起态的时刻(UTC)。
     * NULL = 未因举报预处置。兼作 2h SLA 起点 + 区分 cm-2 发布时挂起（reviewReason=REPORT_P0 亦标来源）。
     */
    @Column(name = "report_hidden_at")
    private Instant reportHiddenAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentPost() {
    }

    /** 非成长日历发布（无事件日期）。委托至带 eventDate 的规范工厂。 */
    public static ContentPost publish(long authorId, ContentType type, Long petId, String text,
            List<String> imageUrls) {
        return publish(authorId, type, petId, text, imageUrls, null);
    }

    public static ContentPost publish(long authorId, ContentType type, Long petId, String text,
            List<String> imageUrls, LocalDate eventDate) {
        ContentPost p = new ContentPost();
        p.authorId = authorId;
        p.type = type;
        p.petId = petId;
        p.text = text;
        p.imageUrls = imageUrls;
        p.eventDate = eventDate;
        p.status = PostStatus.PUBLISHED;
        return p;
    }

    /**
     * 人工审核挂起发布（Story 4.3 / 内容审核 story 2）：未过自动审核但开关已激活时——落库为
     * {@link PostStatus#UNDER_REVIEW}（D-CM2 仅作者可见，不进任何公开口径），等运营处置。
     * 携审核元数据：{@code riskScore}（RISK_HIGH 时有值 / 降级时可空）+ {@code reviewReason}
     * （RISK_HIGH | DEGRADED_FAILCLOSED）。委托规范工厂后改状态并写元数据。
     */
    public static ContentPost pendingReview(long authorId, ContentType type, Long petId, String text,
            List<String> imageUrls, LocalDate eventDate, BigDecimal riskScore, String reviewReason) {
        ContentPost p = publish(authorId, type, petId, text, imageUrls, eventDate);
        p.status = PostStatus.UNDER_REVIEW;
        p.moderationRiskScore = riskScore;
        p.reviewReason = reviewReason;
        return p;
    }

    /** 无审核元数据的挂起（既有调用点 / 测试桩兼容）：委托带元数据工厂，score/reason 省为 null。 */
    public static ContentPost pendingReview(long authorId, ContentType type, Long petId, String text,
            List<String> imageUrls, LocalDate eventDate) {
        return pendingReview(authorId, type, petId, text, imageUrls, eventDate, null, null);
    }

    /** Story 4.3：运营审核通过——UNDER_REVIEW → PUBLISHED，重回公开口径。 */
    public void approveReview() {
        this.status = PostStatus.PUBLISHED;
    }

    /** P0 举报预处置来源标记（cm-6）：review_reason=REPORT_P0，配合 report_hidden_at 供后台队列区分来源。 */
    static final String REVIEW_REASON_REPORT_P0 = "REPORT_P0";

    /**
     * 举报驱动 P0 自动预处置（cm-6 §5.2）：已发布帖翻回「仅作者可见待判」挂起态。
     * PUBLISHED → UNDER_REVIEW + 记 reportHiddenAt(now, UTC) + reviewReason=REPORT_P0（内容不删，deletedAt 保持 NULL）。
     * 调用方须先保证 status==PUBLISHED && reportHiddenAt==NULL（幂等守卫，见 ContentService）。
     */
    public void applyReportHold() {
        this.status = PostStatus.UNDER_REVIEW;
        this.reportHiddenAt = Instant.now();
        this.reviewReason = REVIEW_REASON_REPORT_P0;
    }

    /**
     * P0 举报预处置误报恢复（cm-6 §5.2 判误报）：UNDER_REVIEW → PUBLISHED + 清 reportHiddenAt/reviewReason。
     * 领域层仅改状态；**恢复不得触发 ContentPublishedEvent**（内容原已发布过，里程碑等已 fire）——由 service 层保证不发事件。
     */
    public void releaseReportHold() {
        this.status = PostStatus.PUBLISHED;
        this.reportHiddenAt = null;
        this.reviewReason = null;
    }

    /** 是否处于举报驱动 P0 预处置挂起（cm-6：reportHiddenAt 非空即已预处置）。 */
    public boolean isUnderReportHold() {
        return reportHiddenAt != null;
    }

    /**
     * 审核期版本守卫（内容审核 story 2 · D-CM3 陈旧作废不变量）：审核出结果时比对入队时刻版本，
     * 内容已被改（{@code contentVersion} 已 &gt; 入队版本）→ 旧结果作废、不处置。
     * 帖子编辑端点在 V1 尚不存在 → {@code contentVersion} 恒为 1、此守卫恒返 true（dormant no-op 安全）；
     * 未来编辑 story 接线编辑 +1 后自动生效。
     */
    public boolean matchesContentVersion(int enqueuedVersion) {
        return this.contentVersion == enqueuedVersion;
    }

    /** 软删（Story 3.6 作者删除 / 3.7 运营下架 / 7.3 注销级联）。不物理删，保留行结构。 */
    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    /** Story 4.2：运营恢复已下架内容——清 deletedAt 重回公开口径（评论保持软删、点赞已物删不还原）。 */
    public void restore() {
        this.deletedAt = null;
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

    public Long getAuthorId() {
        return authorId;
    }

    public ContentType getType() {
        return type;
    }

    public Long getPetId() {
        return petId;
    }

    public String getText() {
        return text;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public String getDangerLevel() {
        return dangerLevel;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public PostStatus getStatus() {
        return status;
    }

    public BigDecimal getModerationRiskScore() {
        return moderationRiskScore;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public int getContentVersion() {
        return contentVersion;
    }

    public Instant getReportHiddenAt() {
        return reportHiddenAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

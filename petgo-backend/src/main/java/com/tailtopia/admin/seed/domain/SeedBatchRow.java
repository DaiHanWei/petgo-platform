package com.tailtopia.admin.seed.domain;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ImageSize;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 批量内容里的一行 —— 「存下来但还没发」的那个东西（V1.1.6 Story 13.1）。
 *
 * <p>🔴 <b>刻意是一张独立表，不是往 {@code content_posts} 加个草稿状态</b>（AC4）。
 * 后者会让所有既有的"已发布内容"查询（Feed / 时间线 / 后台内容列表 / 统计 / 举报队列…）
 * 都必须记得排除草稿 —— <b>漏一处就是草稿泄漏到线上</b>。
 * 行发布成功后才在 {@code content_posts} 产生真实内容，并把 id 回填到 {@link #contentPostId}。
 *
 * <p>状态流转全部经 {@link #transitionTo}，合法性由 {@link SeedBatchRowStatus} 定义。
 */
@Entity
@Table(name = "seed_batch_rows")
public class SeedBatchRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private long batchId;

    /**
     * 原始行号。
     *
     * <p>13-3/13-4 要能回显「**第 7 行**的正文超字数」——给内部 id 运营对不上自己那份表格。
     */
    @Column(name = "row_no", nullable = false)
    private int rowNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeedBatchRowStatus status;

    /**
     * 发布账号。
     *
     * <p>🔴 <b>在行上，不在批次上</b>：12-1 的「该账号还有 N 条待发布排期」要按作者统计，
     * 挂在批次上就数不出来。
     */
    @Column(name = "author_user_id", nullable = false)
    private long authorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private ContentType contentType;

    @Column(name = "pet_id")
    private Long petId;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_urls")
    private List<String> imageUrls;

    /** 与 {@link #imageUrls} <b>同序等长</b>（口径同 {@code content_posts.image_sizes}）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_sizes")
    private List<ImageSize> imageSizes;

    /** 计划发布时刻（UTC）。null = 还没排期。 */
    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    /** 发布成功后回填。是「整批撤回」（本版本不做）的数据基础。 */
    @Column(name = "content_post_id")
    private Long contentPostId;

    /**
     * DRAFT 态存**校验错误**、FAILED 态存**发布失败原因**。
     *
     * <p>⚠️ 一行不可能同时处于这两个阶段，所以刻意只有一列 ——
     * 两列会让人不知道该读哪个，而"两个都读、拼起来显示"就更糟。
     */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SeedBatchRow() {
    }

    /** 新录入的一行，初始为 {@link SeedBatchRowStatus#DRAFT}。 */
    public static SeedBatchRow draft(long batchId, int rowNo, long authorUserId, ContentType type,
            Long petId, String body, List<String> imageUrls, List<ImageSize> imageSizes) {
        SeedBatchRow r = new SeedBatchRow();
        r.batchId = batchId;
        r.rowNo = rowNo;
        r.status = SeedBatchRowStatus.DRAFT;
        r.authorUserId = authorUserId;
        r.contentType = type;
        r.petId = petId;
        r.body = body;
        r.imageUrls = imageUrls;
        r.imageSizes = imageSizes;
        r.createdAt = Instant.now();
        r.updatedAt = r.createdAt;
        return r;
    }

    /**
     * 流转到目标状态。**非法流转抛 {@link IllegalStateException}**。
     *
     * <p>🛡 为什么是抛异常而不是返回 false：非法流转说明调用方对这一行的状态判断错了，
     * 而"返回 false 被忽略"会让那个错误静默留在数据里 —— 比如一条已发布的内容
     * 在后台显示成"待发布"，运营再点一次发布就成了重复发帖。
     *
     * <p>各状态附带的不变式一并在这里守：
     * <ul>
     *   <li>{@code → SCHEDULED} 必须已有 {@link #scheduledAt}（否则"到点"永远不会到）</li>
     *   <li>{@code → PUBLISHED} 必须已有 {@link #contentPostId}（否则回填就白做了）</li>
     *   <li>{@code → DRAFT} 清掉排期与失败原因（"取消排期"之后还留着计划时间会误导人）</li>
     * </ul>
     */
    public void transitionTo(SeedBatchRowStatus target) {
        if (!status.canGoTo(target)) {
            throw new IllegalStateException("非法状态流转：" + status + " → " + target);
        }
        if (target == SeedBatchRowStatus.SCHEDULED && scheduledAt == null) {
            throw new IllegalStateException("排期前必须先设定计划发布时间");
        }
        if (target == SeedBatchRowStatus.PUBLISHED && contentPostId == null) {
            throw new IllegalStateException("标记为已发布前必须先回填内容 id");
        }
        if (target == SeedBatchRowStatus.DRAFT) {
            this.scheduledAt = null;
            this.errorMessage = null;
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    /** 排期时间。**先设时间、再流转**（{@link #transitionTo} 会校验它非空）。 */
    public void setScheduledAt(Instant at) {
        this.scheduledAt = at;
        this.updatedAt = Instant.now();
    }

    /** 回填发布结果。**先回填、再流转**。 */
    public void setContentPostId(Long contentPostId) {
        this.contentPostId = contentPostId;
        this.updatedAt = Instant.now();
    }

    public void setErrorMessage(String message) {
        this.errorMessage = message == null || message.length() <= 500
                ? message
                // 截断而不是抛：错误信息本身不该成为新的失败原因。
                : message.substring(0, 500);
        this.updatedAt = Instant.now();
    }

    public void edit(ContentType type, Long petId, String body, List<String> imageUrls,
            List<ImageSize> imageSizes) {
        this.contentType = type;
        this.petId = petId;
        this.body = body;
        this.imageUrls = imageUrls;
        this.imageSizes = imageSizes;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public long getBatchId() {
        return batchId;
    }

    public int getRowNo() {
        return rowNo;
    }

    public SeedBatchRowStatus getStatus() {
        return status;
    }

    public long getAuthorUserId() {
        return authorUserId;
    }

    public ContentType getContentType() {
        return contentType;
    }

    public Long getPetId() {
        return petId;
    }

    public String getBody() {
        return body;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public List<ImageSize> getImageSizes() {
        return imageSizes;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Long getContentPostId() {
        return contentPostId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

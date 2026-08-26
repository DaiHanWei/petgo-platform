package com.tailtopia.admin.seed.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import com.tailtopia.content.domain.ContentType;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 批量内容的批次容器（V1.1.6 Story 13.1 · AC2/AC3）。
 *
 * <p>🛡 <b>本类刻意没有 status 字段</b>，这不是漏了 —— 状态在 {@link SeedBatchRow} 上。
 * 想在这里加一个"批次状态"之前，先想一遍「47 已发布 / 5 排期中 / 3 待修正」这个**常态**
 * 该叫什么状态，以及"改某一行的计划时间"该落在哪。
 *
 * <p>它的职责只有两件：<b>分组</b>（把哪些行算作一批）与 <b>审计</b>（谁在什么时候提交的）。
 * 内容清单不在这里，而是通过行反查 —— 那也是「整批撤回」（本版本不做）的数据基础。
 */
@Entity
@Table(name = "seed_batches")
public class SeedBatch {

    /** 这批是怎么进来的。 */
    public enum Source {
        /** 在线粘贴多行。 */
        ONLINE_PASTE,
        /** Excel 导入。 */
        EXCEL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    @Column(name = "created_by", nullable = false, updatable = false)
    private long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 批次级默认发布账号（V1.1.6 Story 13.3 · AC1/AC5）。
     *
     * <p>🔴 <b>它的存在消除了两处重复的账号下拉</b>：此前在线录入与 Excel 导入
     * 各带一个一模一样的下拉。而逐行必填意味着 50 行填 50 次、其中大多数是同一个值 ——
     * 纯重复劳动，且手打账号名比选下拉更易错（§7.5 第 2 条）。
     *
     * <p>⚠️ 这**覆盖**了 V1.1.0 原「发布账号留空 = 校验失败、视为必填缺失」的规则。
     */
    @Column(name = "default_author_user_id")
    private Long defaultAuthorUserId;

    /**
     * 批次默认内容类型。
     *
     * <p>🔴 只允许 {@code DAILY} / {@code KNOWLEDGE} —— **批量不支持
     * {@code GROWTH_MOMENT}**（A-10）。理由已从"做不到"变为"**不该做**"：
     * 运营真实账号有宠物档案后技术上可行了，但成长日历需逐行绑定具体宠物与事件日期、
     * 且属"真实记录"性质，不适合批量灌入。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_content_type", length = 20)
    private ContentType defaultContentType;

    /** 批次默认计划发布时间。{@code null} = 立即发布。 */
    @Column(name = "default_scheduled_at")
    private Instant defaultScheduledAt;

    protected SeedBatch() {
    }

    /** 页头那一处批次级设置。三项都可留空。 */
    public void applyDefaults(Long authorUserId, ContentType type, Instant scheduledAt) {
        this.defaultAuthorUserId = authorUserId;
        this.defaultContentType = type;
        this.defaultScheduledAt = scheduledAt;
    }

    public static SeedBatch open(Source source, long adminAccountId) {
        SeedBatch b = new SeedBatch();
        b.source = source;
        b.createdBy = adminAccountId;
        b.createdAt = Instant.now();
        return b;
    }

    public Long getId() {
        return id;
    }

    public Source getSource() {
        return source;
    }

    public long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getDefaultAuthorUserId() {
        return defaultAuthorUserId;
    }

    public ContentType getDefaultContentType() {
        return defaultContentType;
    }

    public Instant getDefaultScheduledAt() {
        return defaultScheduledAt;
    }
}

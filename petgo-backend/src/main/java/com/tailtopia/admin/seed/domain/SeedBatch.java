package com.tailtopia.admin.seed.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

    protected SeedBatch() {
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
}

package com.tailtopia.admin.seed.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 批次里的一张素材图（V1.1.6 Story 13.2 · AB-3K Step 1）。
 *
 * <p>🔴 <b>为什么素材要挂批次</b>：Step 1 的图是**先落对象存储再回显缩略图**的 ——
 * 运营刚拖进去图就已经在存储里，而此时<b>还没有任何内容行引用它</b>。
 * 不挂批次的话，"拖错文件夹关掉页面 / 填一半放弃 / 整批校验没过"这些图会永久留在存储里，
 * <b>不报错、不影响功能、无人会发现</b>，只是账单慢慢涨。
 *
 * <p>⚠️ <b>{@link #orphanedAt} 是"记账"不是"已删"</b>（2026-08-24 用户拍板）：
 * 既有决策 F21 明令 OSS 对象任何情况不物理删除、删除原语已整体移除，
 * 并要求"确需删除先回 F21 重新拍板"。本 story 不去打破它 ——
 * 改为把废弃素材标记出来并<b>留住 {@link #objectKey} 与 {@link #sizeBytes}</b>，
 * 于是泄漏从「无人知道」变成「有账可查」。
 * 🛡 所以清理时**不删这张表的行**。
 */
@Entity
@Table(name = "seed_batch_assets")
public class SeedBatchAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private long batchId;

    /** 运营那边的原始文件名。查重与清单回显都用它 —— <b>运营认的是文件名，不是 URL</b>。 */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** 🔴 对象存储 key。回收要靠它，所以标记废弃时绝不能丢。 */
    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(nullable = false, length = 1024)
    private String url;

    /** 原始宽（0 = 测不出来）。 */
    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * 文件内容 SHA-256（hex，bug 20260901-467）。素材级查重的判据 ——
     * 同内容改名/跨批重传都靠它识别。⚠️ 存量行为 null（不回填，不参与查重）。
     */
    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 非空 = 已判定可回收。<b>不代表已从存储删除</b>（见类注释）。 */
    @Column(name = "orphaned_at")
    private Instant orphanedAt;

    protected SeedBatchAsset() {
    }

    public static SeedBatchAsset of(long batchId, String fileName, String objectKey, String url,
            int width, int height, long sizeBytes) {
        SeedBatchAsset a = new SeedBatchAsset();
        a.batchId = batchId;
        a.fileName = fileName;
        a.objectKey = objectKey;
        a.url = url;
        a.width = width;
        a.height = height;
        a.sizeBytes = sizeBytes;
        a.createdAt = Instant.now();
        return a;
    }

    /** 标记为可回收。**幂等** —— 重复扫描不该把时间往后推（那会让"废弃了多久"失真）。 */
    public void markOrphaned() {
        if (orphanedAt == null) {
            orphanedAt = Instant.now();
        }
    }

    public boolean isOrphaned() {
        return orphanedAt != null;
    }

    public Long getId() {
        return id;
    }

    public long getBatchId() {
        return batchId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getUrl() {
        return url;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public void setContentSha256(String v) {
        this.contentSha256 = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getOrphanedAt() {
        return orphanedAt;
    }
}

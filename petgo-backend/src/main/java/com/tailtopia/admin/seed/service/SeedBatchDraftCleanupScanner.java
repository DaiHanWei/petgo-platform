package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.seed.domain.SeedBatchAsset;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.repository.SeedBatchAssetRepository;
import com.tailtopia.admin.seed.repository.SeedBatchRepository;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 废弃草稿与素材的清理（V1.1.6 Story 13.2 · AC4）。
 *
 * <h2>🔴 不做这件事的后果是**静默的存储泄漏**</h2>
 * Step 1 的图是先落对象存储再回显缩略图的 —— 运营刚拖进去图就已经在存储里，
 * 而此时还没有任何内容行引用它。"拖错文件夹关掉页面 / 填一半放弃 / 整批校验没过"
 * 这些图会永久留下，<b>不报错、不影响功能、无人会发现</b>，只是账单慢慢涨
 * （按上限，单个废弃批次最多 500MB）。
 *
 * <h2>🔴🛡 清理对象是**行**，不是批次</h2>
 * <b>按批次清理是事故</b>：会把同批次里<b>已排期的行一起删掉</b>。
 * 状态是行级的（13-1 AC2）——「47 已发布 / 5 排期中 / 3 还是草稿」是常态，
 * 而那 3 条草稿过期，绝不意味着另外 52 条也该消失。
 *
 * <h2>⚠️ 素材只"记账"，不物理删除</h2>
 * 既有决策 F21（2026-08-19）明令 OSS 对象任何情况不物理删除、删除原语已整体移除。
 * 2026-08-24 用户拍板：<b>本 story 不打破 F21</b>，改为把废弃素材标记出来并留住
 * 对象 key 与占用字节 —— 泄漏从「无人知道」变成「有账可查」，
 * 将来决定回收时是一条 SQL 的事。
 *
 * <p>做法照抄后台既有的 8 处扫描器：{@code @Scheduled} + DB 状态守卫，
 * <b>禁 MQ / 延迟队列</b>（架构 enforcement 护栏）。幂等：过期集合本身就是守卫。
 */
@Component
public class SeedBatchDraftCleanupScanner {

    private static final Logger log = LoggerFactory.getLogger(SeedBatchDraftCleanupScanner.class);

    private final SeedBatchRowRepository rows;
    private final SeedBatchAssetRepository assets;
    private final SeedBatchRepository batches;

    /**
     * 草稿保留天数。
     *
     * <p>🔴 <b>7 天偏短是刻意的</b>（A-17）：草稿积压本身没有价值，
     * 而素材占的是真实存储成本。
     */
    private final Duration keep;

    public SeedBatchDraftCleanupScanner(SeedBatchRowRepository rows,
            SeedBatchAssetRepository assets, SeedBatchRepository batches,
            @Value("${petgo.seed-batch.draft-keep-days:7}") int keepDays) {
        this.rows = rows;
        this.assets = assets;
        this.batches = batches;
        this.keep = Duration.ofDays(keepDays);
    }

    @Scheduled(fixedDelayString = "${petgo.seed-batch.cleanup-scan-ms:3600000}")
    @Transactional
    public void cleanupExpiredDrafts() {
        Instant deadline = Instant.now().minus(keep);
        // 🔴 只挑**草稿**行，且按最后修改时间 —— 运营改过一次就该重新计时，
        //    否则一个被持续编辑了 8 天的批次会在他眼前被清掉。
        List<SeedBatchRow> expired =
                rows.findByStatusAndUpdatedAtLessThan(SeedBatchRowStatus.DRAFT, deadline);
        Set<Long> touchedBatches = new LinkedHashSet<>();
        for (SeedBatchRow r : expired) {
            touchedBatches.add(r.getBatchId());
        }
        // 另一类：**一行都没录就放弃的批次**（拖完图就关页面）。
        // 它没有任何过期草稿行，所以上面那一轮碰不到它 —— 而它恰恰是最典型的泄漏来源。
        for (var b : batches.findAll()) {
            if (b.getCreatedAt().isBefore(deadline)
                    && rows.findByBatchIdOrderByRowNoAsc(b.getId()).isEmpty()) {
                touchedBatches.add(b.getId());
            }
        }
        if (!expired.isEmpty()) {
            rows.deleteAll(expired);
        }
        if (touchedBatches.isEmpty()) {
            return;
        }

        int orphaned = 0;
        int removedBatches = 0;
        for (Long batchId : touchedBatches) {
            List<SeedBatchRow> remaining = rows.findByBatchIdOrderByRowNoAsc(batchId);
            // 🛡 **仍被任何存留行引用的素材不动**。
            //
            // ⚠️ 这里刻意比 AC 的字面要求更严：AC 写的是"不被任何**非草稿**行引用时才删"，
            //    但一条**刚建的草稿**（第 1 天）同样在用那些图 —— 照字面做会把
            //    它的图从底下抽走，运营回来看到一墙裂图。所以判据是"任何存留行"。
            Set<String> stillUsed = new HashSet<>();
            for (SeedBatchRow r : remaining) {
                if (r.getImageUrls() != null) {
                    stillUsed.addAll(r.getImageUrls());
                }
            }
            for (SeedBatchAsset a : assets.findByBatchIdAndOrphanedAtIsNull(batchId)) {
                if (!stillUsed.contains(a.getUrl())) {
                    a.markOrphaned();
                    assets.save(a);
                    orphaned++;
                }
            }
            // 批次记录在**行清空 + 素材也没有存留**之后才消失。
            //
            // ⚠️ 这一条比 AC 的「所有行都被清理干净后批次记录消失」多加了后半个条件：
            //    批次行是那份废弃台账的**唯一归属信息**（"这 500MB 是 8 月 24 日谁拖进来的"）。
            //    删掉它，泄漏就从"有账可查"退回"无人知道"—— 而那正是 AC4 存在的理由。
            boolean noAssetsLeft = assets.findByBatchIdOrderByIdAsc(batchId).isEmpty();
            if (remaining.isEmpty() && noAssetsLeft) {
                batches.deleteById(batchId);
                removedBatches++;
            }
        }
        log.info("批量草稿清理：删除草稿行 {} 条，标记废弃素材 {} 张，回收批次记录 {} 个，"
                        + "累计待回收字节 {}（F21 未反转，对象未物理删除）",
                expired.size(), orphaned, removedBatches, assets.totalOrphanedBytes());
    }
}

package com.tailtopia.admin.seed.repository;

import com.tailtopia.admin.seed.domain.SeedBatchAsset;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 批次素材仓储（V1.1.6 Story 13.2）。 */
public interface SeedBatchAssetRepository extends JpaRepository<SeedBatchAsset, Long> {

    /** 缩略图墙：按上传顺序。 */
    List<SeedBatchAsset> findByBatchIdOrderByIdAsc(long batchId);

    /**
     * 还没被判定废弃的素材 —— 上限统计与查重都只看这些。
     *
     * <p>🔴 已废弃的不算进上限：那批图已经不属于这个批次的工作集了，
     * 算进去会让运营在一个"已经放弃过一次"的批次里凭空少掉配额。
     */
    List<SeedBatchAsset> findByBatchIdAndOrphanedAtIsNull(long batchId);

    /**
     * 同批同名查重 —— 🔴 只看**在用**的（bug 20260901-474）：素材可单删（标记废弃）后，
     * 「删掉再传同名文件」是替换的正当路径，把废弃行算进查重会让替换永远被拒。
     * DB 侧的部分唯一索引（{@code WHERE orphaned_at IS NULL}）与此同一口径。
     */
    Optional<SeedBatchAsset> findByBatchIdAndFileNameAndOrphanedAtIsNull(long batchId, String fileName);

    /** 废弃素材台账（供后续回收决策用；F21 反转后一条 SQL 即可动手）。 */
    @Query("select coalesce(sum(a.sizeBytes), 0) from SeedBatchAsset a where a.orphanedAt is not null")
    long totalOrphanedBytes();

    /**
     * 素材级内容查重（bug 20260901-467）：同内容哈希的**在用**素材，跨批次全表查。
     *
     * <p>存量行哈希为 null，天然不会命中（调用方也不得拿 null 来查）。
     */
    List<SeedBatchAsset> findByContentSha256AndOrphanedAtIsNullOrderByIdAsc(String contentSha256);

    /** 一批素材的哈希批量取（指纹解析用，见 {@code SeedBatchAssetService#fingerprintKeys}）。 */
    List<SeedBatchAsset> findByUrlIn(java.util.Collection<String> urls);
}

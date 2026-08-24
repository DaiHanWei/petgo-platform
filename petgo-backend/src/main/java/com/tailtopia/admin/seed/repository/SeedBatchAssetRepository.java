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

    Optional<SeedBatchAsset> findByBatchIdAndFileName(long batchId, String fileName);

    /** 废弃素材台账（供后续回收决策用；F21 反转后一条 SQL 即可动手）。 */
    @Query("select coalesce(sum(a.sizeBytes), 0) from SeedBatchAsset a where a.orphanedAt is not null")
    long totalOrphanedBytes();
}

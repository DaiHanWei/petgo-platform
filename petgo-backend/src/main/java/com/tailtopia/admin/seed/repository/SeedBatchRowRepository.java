package com.tailtopia.admin.seed.repository;

import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 批量内容行仓储（V1.1.6 Story 13.1）。 */
public interface SeedBatchRowRepository extends JpaRepository<SeedBatchRow, Long> {

    /** 一个批次的全部行，按原始行号 —— 运营看的是自己那份表格的顺序。 */
    List<SeedBatchRow> findByBatchIdOrderByRowNoAsc(long batchId);

    List<SeedBatchRow> findByBatchIdInOrderByRowNoAsc(List<Long> batchIds);

    /**
     * 某账号还有几条**已排期未发布**（V1.1.6 Story 12.1 · AC4）。
     *
     * <p>🔴 只数 {@code SCHEDULED}：草稿与待确认还没被安排出去，
     * 运营移出账号时它们不会"到点失败"，混进来会把提示的数字说大。
     */
    long countByAuthorUserIdAndStatus(long authorUserId, SeedBatchRowStatus status);

    /**
     * 到点该发的行（13-5 用）。
     *
     * <p>⚠️ 本 story <b>不做</b>到点发布，这个方法先摆在这里是因为
     * 索引 {@code ix_seed_batch_rows_scheduled} 的形状要由它决定 ——
     * 索引建错了之后再改要动迁移。
     */
    List<SeedBatchRow> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            SeedBatchRowStatus status, Instant deadline);
}

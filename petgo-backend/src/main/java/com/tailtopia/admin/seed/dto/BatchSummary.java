package com.tailtopia.admin.seed.dto;

import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 批次列表里的一行 —— <b>按其各行状态聚合</b>（V1.1.6 Story 13.1 · AC2）。
 *
 * <p>🛡 这个 record 就是"批次没有状态"这个决定的落地形式：
 * 批次能显示的只是「本批 50 条：47 已发布 / 5 排期中 / 3 待修正」这样一份**统计**，
 * 而不是一个单一状态字段。
 *
 * @param counts 各状态的条数。**只包含出现过的状态** —— 显示层据此渲染，
 *               不必为 0 的那些留位置（「50 条：50 已发布」比
 *               「50 条：50 已发布 / 0 排期中 / 0 待修正 / 0 失败」好读）
 */
public record BatchSummary(
        long batchId,
        SeedBatch.Source source,
        long createdBy,
        Instant createdAt,
        int total,
        Map<SeedBatchRowStatus, Integer> counts) {

    /** 从批次与它的行聚合出来。 */
    public static BatchSummary of(SeedBatch batch, List<SeedBatchRow> rows) {
        Map<SeedBatchRowStatus, Integer> counts = new java.util.EnumMap<>(SeedBatchRowStatus.class);
        for (SeedBatchRow r : rows) {
            counts.merge(r.getStatus(), 1, Integer::sum);
        }
        return new BatchSummary(batch.getId(), batch.getSource(), batch.getCreatedBy(),
                batch.getCreatedAt(), rows.size(), counts);
    }

    /** 是否整批都发完了（列表上可据此淡化显示）。 */
    public boolean allPublished() {
        return total > 0 && counts.getOrDefault(SeedBatchRowStatus.PUBLISHED, 0) == total;
    }

    /** 还有没有没发出去的（草稿 / 待确认 / 已排期）。 */
    public int pendingCount() {
        return counts.entrySet().stream()
                .filter(e -> e.getKey().isPending())
                .mapToInt(Map.Entry::getValue).sum();
    }
}
